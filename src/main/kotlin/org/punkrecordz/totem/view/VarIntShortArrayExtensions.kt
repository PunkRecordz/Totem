package org.punkrecordz.totem.view

import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.concurrent.awaitResult
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

    if (expectedSize < PARALLEL_THRESHOLD_ELEMENTS || sourceSlice.byteSize() < PARALLEL_THRESHOLD_ELEMENTS) {
        TotemSys.decodeVarIntShorts(
            sourceSlice,
            destinationSlice,
            expectedSize,
        )

        return view
    }

    val availableCores = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
    val byteBoundaries = LongArray(availableCores + 1)
    val partitionShortOffsets = IntArray(availableCores)
    val partitionShortCounts = IntArray(availableCores)

    val rawSourceAddress = sourceSlice.address()
    val rawDestinationAddress = destinationSlice.address()

    Arena.ofConfined().use { tempArena ->
        val byteStartsSegment = tempArena.allocate(ValueLayout.JAVA_LONG, (availableCores + 1).toLong())

        TotemSys.findVarIntBoundaries(
            sourceSlice,
            availableCores,
            byteStartsSegment,
        )

        MemorySegment.copy(byteStartsSegment, ValueLayout.JAVA_LONG, 0L, byteBoundaries, 0, availableCores + 1)
    }

    val countTasks = (0 until availableCores).map { index ->
        val startByte = byteBoundaries[index]
        val byteLength = byteBoundaries[index + 1] - startByte

        Callable {
            if (byteLength > 0L) {
                val chunkSlice = MemorySegment.ofAddress(rawSourceAddress + startByte)
                    .reinterpret(byteLength)

                TotemSys.countVarIntTerminals(chunkSlice).toInt()
            } else {
                0
            }
        }
    }

    val countFutures = TotemThreadPool.executor.invokeAll(countTasks)
    var totalDecodedShorts = 0

    for (index in 0 until availableCores) {
        val count = countFutures[index].awaitResult()
        val remainingNeeded = (expectedSize - totalDecodedShorts).coerceAtLeast(0)
        val assignedCount = minOf(count, remainingNeeded)

        partitionShortOffsets[index] = totalDecodedShorts
        partitionShortCounts[index] = assignedCount
        totalDecodedShorts += assignedCount
    }

    if (totalDecodedShorts < expectedSize) {
        throw IllegalArgumentException(
            "Incomplete VarInt data: expected $expectedSize elements, but buffer contained $totalDecodedShorts VarInt values.",
        )
    }

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
        future.awaitResult()
    }

    return view
}

fun ShortView.toVarIntByteArray(
    arena: Arena,
): ByteArrayTag {
    if (this !is NativeShortView) {
        throw IllegalArgumentException("Only NativeShortView is supported for native VarInt encoding.")
    }

    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val elementCount = this.size.toLong()

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
        sizeFutures[index].awaitResult()
    }

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
        future.awaitResult()
    }

    return NativeByteArrayTag(targetSegment)
}
