package org.punkrecordz.totem.view

import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Callable

private const val PARALLEL_THRESHOLD_ELEMENTS: Long = 65536L

/**
 * Decodes the VarInt compressed byte data inside this [ByteView] into a zero-allocation off-heap [NativeShortView].
 *
 * Strategy:
 * For large datasets exceeding [PARALLEL_THRESHOLD_ELEMENTS], partitions the source VarInt stream
 * along exact terminal byte boundaries (MSB = 0) and counts terminal markers across partitions using AVX2 SIMD.
 * Once partition bounds and destination offsets are established in sub-millisecond time, worker threads
 * decode disjoint byte slices concurrently directly into the contiguous target [NativeShortView] off-heap segment.
 * By reinterpreting raw off-heap memory addresses, worker threads can safely access segments even when the caller
 * allocated memory inside a thread-confined [Arena], while synchronous completion guarantees memory validity.
 *
 * Performance targets:
 * Sub-5ms VarInt decoding for tens of millions of block states across available hardware threads with zero heap GC.
 *
 * @param expectedSize The number of Short elements expected in the decoded view.
 * @param arena The FFM Arena to allocate the off-heap MemorySegment for the resulting view.
 */
fun ByteView.toVarIntShortArray(
    expectedSize: Int,
    arena: Arena,
): NativeShortView {
    if (this !is NativeByteArrayTag) {
        throw IllegalArgumentException("Only native off-heap tags can be decoded using native VarInt translation.")
    }

    val view = NativeShortView.of(
        expectedSize,
        arena,
    )

    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val destinationSlice = view.segment.asSlice(MemoryLayouts.INT.byteSize())

    // sequential fast path for smaller payloads
    if (expectedSize < PARALLEL_THRESHOLD_ELEMENTS || sourceSlice.byteSize() < PARALLEL_THRESHOLD_ELEMENTS) {
        TotemSys.decodeVarIntShorts(
            sourceSlice,
            destinationSlice,
            expectedSize,
        )

        return view
    }

    val totalBytes = sourceSlice.byteSize()
    val availableCores = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
    val targetChunkBytes = totalBytes / availableCores

    // partition byte buffer along VarInt terminal boundaries (byte & 0x80 == 0)
    val byteBoundaries = LongArray(availableCores + 1)
    byteBoundaries[0] = 0L
    byteBoundaries[availableCores] = totalBytes

    for (index in 1 until availableCores) {
        var boundary = minOf(totalBytes, index.toLong() * targetChunkBytes)
        while (boundary < totalBytes && (sourceSlice.get(ValueLayout.JAVA_BYTE, boundary - 1L).toInt() and 0x80) != 0) {
            boundary++
        }

        byteBoundaries[index] = boundary
    }

    val partitionShortCounts = IntArray(availableCores)
    val partitionShortOffsets = IntArray(availableCores)
    var totalDecodedShorts = 0

    // count terminals in each partition sequentially via fast AVX2 SIMD (<0.2ms total)
    for (index in 0 until availableCores) {
        val startByte = byteBoundaries[index]
        val byteLength = byteBoundaries[index + 1] - startByte
        val shortCount = if (byteLength <= 0L) {
            0
        } else {
            val chunkSlice = sourceSlice.asSlice(startByte, byteLength)
            TotemSys.countVarIntTerminals(chunkSlice).toInt()
        }

        partitionShortOffsets[index] = totalDecodedShorts
        partitionShortCounts[index] = shortCount
        totalDecodedShorts += shortCount
    }

    if (totalDecodedShorts != expectedSize) {
        throw IllegalArgumentException(
            "Incomplete VarInt data: expected $expectedSize elements, but buffer contained $totalDecodedShorts VarInt values.",
        )
    }

    val rawSourceAddress = sourceSlice.address()
    val rawDestinationAddress = destinationSlice.address()

    // decode each partition concurrently directly into destination slice
    val decodeTasks = (0 until availableCores).map { index ->
        val startByte = byteBoundaries[index]
        val byteLength = byteBoundaries[index + 1] - startByte
        val startShort = partitionShortOffsets[index]
        val shortCount = partitionShortCounts[index]

        Callable {
            if (shortCount > 0) {
                val chunkSourceSlice = MemorySegment.ofAddress(rawSourceAddress + startByte)
                    .reinterpret(byteLength)
                val chunkDestinationSlice = MemorySegment.ofAddress(
                    rawDestinationAddress + (startShort.toLong() * Short.SIZE_BYTES),
                ).reinterpret(shortCount.toLong() * Short.SIZE_BYTES)

                TotemSys.decodeVarIntShorts(
                    chunkSourceSlice,
                    chunkDestinationSlice,
                    shortCount,
                )
            }
        }
    }

    val decodeFutures = TotemThreadPool.executor.invokeAll(decodeTasks)
    for (future in decodeFutures) {
        future.get()
    }

    return view
}

/**
 * Encodes this [ShortView] into a VarInt compressed [ByteArrayTag] off-heap.
 *
 * Strategy:
 * For large datasets exceeding [PARALLEL_THRESHOLD_ELEMENTS], partitions the input shorts across
 * available processor cores. First computes the exact encoded byte size for each chunk concurrently
 * using vectorized AVX2 SIMD routines. After a single prefix sum pass, allocates the exact target
 * segment once and encodes all chunks concurrently directly into their final destination slices.
 * Raw memory segment reinterpretation enables cross-thread dispatch even with caller-confined arenas.
 *
 * Performance targets:
 * Sub-5ms VarInt encoding directly into the target [arena] with zero temporary reallocations or memory copies.
 *
 * @param arena The FFM Arena to allocate the off-heap MemorySegment for the resulting ByteArrayTag.
 */
fun ShortView.toVarIntByteArray(
    arena: Arena,
): ByteArrayTag {
    if (this !is NativeShortView) {
        throw IllegalArgumentException("Only NativeShortView is supported for native VarInt encoding.")
    }

    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val elementCount = this.size.toLong()

    // sequential fast path for smaller payloads
    if (elementCount < PARALLEL_THRESHOLD_ELEMENTS) {
        val payloadSize = TotemSys.calculateVarIntEncodedSize(sourceSlice, elementCount)
        val totalSize = payloadSize + MemoryLayouts.INT.byteSize()

        val targetSegment = arena.allocateUninitialized(totalSize)
        targetSegment.set(MemoryLayouts.INT, 0L, payloadSize.toInt())

        val destinationSlice = targetSegment.asSlice(MemoryLayouts.INT.byteSize())
        TotemSys.encodeShortsVarInt(
            sourceSlice,
            elementCount,
            destinationSlice,
        )

        return NativeByteArrayTag(targetSegment)
    }

    val availableCores = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
    val chunkSize = (elementCount + availableCores - 1) / availableCores
    val partitionCount = ((elementCount + chunkSize - 1) / chunkSize).toInt()

    val partitionStartIndices = LongArray(partitionCount)
    val partitionElementCounts = LongArray(partitionCount)

    for (index in 0 until partitionCount) {
        val start = index.toLong() * chunkSize
        val count = minOf(chunkSize, elementCount - start)
        partitionStartIndices[index] = start
        partitionElementCounts[index] = count
    }

    val rawSourceAddress = sourceSlice.address()

    // calculate partition encoded byte sizes concurrently
    val sizeTasks = (0 until partitionCount).map { index ->
        val start = partitionStartIndices[index]
        val count = partitionElementCounts[index]

        Callable {
            val partitionSlice = MemorySegment.ofAddress(
                rawSourceAddress + (start * Short.SIZE_BYTES),
            ).reinterpret(count * Short.SIZE_BYTES)

            TotemSys.calculateVarIntEncodedSize(partitionSlice, count)
        }
    }

    val sizeFutures = TotemThreadPool.executor.invokeAll(sizeTasks)
    val partitionByteSizes = LongArray(partitionCount) { index ->
        sizeFutures[index].get()
    }

    // compute output prefix offsets
    val partitionByteOffsets = LongArray(partitionCount)
    var totalPayloadSize = 0L

    for (index in 0 until partitionCount) {
        partitionByteOffsets[index] = totalPayloadSize
        totalPayloadSize += partitionByteSizes[index]
    }

    val totalSize = totalPayloadSize + MemoryLayouts.INT.byteSize()
    val targetSegment = arena.allocateUninitialized(totalSize)
    targetSegment.set(MemoryLayouts.INT, 0L, totalPayloadSize.toInt())

    val destinationSlice = targetSegment.asSlice(MemoryLayouts.INT.byteSize())
    val rawDestinationAddress = destinationSlice.address()

    // encode partitions concurrently directly into disjoint slices of target segment
    val encodeTasks = (0 until partitionCount).map { index ->
        val start = partitionStartIndices[index]
        val count = partitionElementCounts[index]
        val destinationOffset = partitionByteOffsets[index]
        val destinationLength = partitionByteSizes[index]

        Callable {
            val partitionSourceSlice = MemorySegment.ofAddress(
                rawSourceAddress + (start * Short.SIZE_BYTES),
            ).reinterpret(count * Short.SIZE_BYTES)

            val partitionDestinationSlice = MemorySegment.ofAddress(
                rawDestinationAddress + destinationOffset,
            ).reinterpret(destinationLength)

            TotemSys.encodeShortsVarInt(
                partitionSourceSlice,
                count,
                partitionDestinationSlice,
            )
        }
    }

    val encodeFutures = TotemThreadPool.executor.invokeAll(encodeTasks)
    for (future in encodeFutures) {
        future.get()
    }

    return NativeByteArrayTag(targetSegment)
}
