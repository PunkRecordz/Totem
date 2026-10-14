package org.punkrecordz.totem.view

import org.punkrecordz.totem.codec.BlockDataCodec
import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.ffi.TotemBitPackSys
import org.punkrecordz.totem.impl.heap.HeapLongArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.LongArrayTag
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.Callable

private const val PARALLEL_THRESHOLD_ELEMENTS: Int = 262_144

fun LongView.toBitPackedShortArray(
    expectedSize: Int,
    bitsPerEntry: Int,
    arena: Arena,
    precise: Boolean = false,
): NativeShortView {
    require(expectedSize >= 0) { "expectedSize must be non-negative, but was $expectedSize" }
    require(bitsPerEntry in 1..16) { "bitsPerEntry must be in 1..16, but was $bitsPerEntry" }

    val view = NativeShortView.of(expectedSize, arena)
    if (expectedSize == 0) return view

    val destinationSlice = view.segment.asSlice(MemoryLayouts.INT.byteSize())

    when (this) {
        is NativeLongArrayTag -> {
            val longsCount = this.size
            val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())

            if (expectedSize >= PARALLEL_THRESHOLD_ELEMENTS && longsCount > 0) {
                unpackNativeParallel(
                    sourceSlice,
                    longsCount,
                    destinationSlice,
                    expectedSize,
                    bitsPerEntry,
                    precise,
                )
            } else {
                TotemBitPackSys.unpackLongsToShorts(
                    sourceSlice,
                    longsCount.toLong(),
                    destinationSlice,
                    expectedSize.toLong(),
                    bitsPerEntry,
                    precise,
                    bigEndianSource = true,
                )
            }
        }
        is HeapLongArrayTag -> {
            val array = this.array
            TotemBitPackSys.unpackLongsToShorts(
                MemorySegment.ofArray(array),
                array.size.toLong(),
                destinationSlice,
                expectedSize.toLong(),
                bitsPerEntry,
                precise,
                bigEndianSource = false,
            )
        }
        else -> {
            val fallbackArray = LongArray(this.size) { index -> this[index] }
            TotemBitPackSys.unpackLongsToShorts(
                MemorySegment.ofArray(fallbackArray),
                fallbackArray.size.toLong(),
                destinationSlice,
                expectedSize.toLong(),
                bitsPerEntry,
                precise,
                bigEndianSource = false,
            )
        }
    }

    return view
}

fun ShortView.toBitPackedLongArray(
    bitsPerEntry: Int,
    arena: Arena,
    precise: Boolean = false,
): LongArrayTag {
    require(bitsPerEntry in 1..16) { "bitsPerEntry must be in 1..16, but was $bitsPerEntry" }

    val elementCount = this.size
    val longsCount = BlockDataCodec.calculatePackedLongCount(elementCount, bitsPerEntry, precise)
    val headerSize = MemoryLayouts.INT.byteSize()
    val targetSegment = arena.allocateUninitialized(headerSize + (longsCount.toLong() * Long.SIZE_BYTES))
    targetSegment.set(MemoryLayouts.INT, 0L, longsCount)

    if (longsCount == 0) {
        return NativeLongArrayTag(targetSegment)
    }

    val destinationSlice = targetSegment.asSlice(headerSize)

    if (this is NativeShortView) {
        val sourceSlice = this.segment.asSlice(headerSize)

        if (elementCount >= PARALLEL_THRESHOLD_ELEMENTS) {
            packNativeParallel(
                sourceSlice,
                elementCount,
                destinationSlice,
                bitsPerEntry,
                precise,
            )
        } else {
            TotemBitPackSys.packShortsToLongs(
                sourceSlice,
                elementCount.toLong(),
                destinationSlice,
                longsCount.toLong(),
                bitsPerEntry,
                precise,
                bigEndianDestination = true,
            )
        }
    } else {
        val fallbackShorts = ShortArray(elementCount) { index -> this[index] }
        TotemBitPackSys.packShortsToLongs(
            MemorySegment.ofArray(fallbackShorts),
            elementCount.toLong(),
            destinationSlice,
            longsCount.toLong(),
            bitsPerEntry,
            precise,
            bigEndianDestination = true,
        )
    }

    return NativeLongArrayTag(targetSegment)
}

private fun unpackNativeParallel(
    sourceSlice: MemorySegment,
    longsCount: Int,
    destinationSlice: MemorySegment,
    expectedSize: Int,
    bitsPerEntry: Int,
    precise: Boolean,
) {
    val blockElements = if (precise) 64 else (64 / bitsPerEntry)
    val blockWords = if (precise) bitsPerEntry else 1
    val totalBlocks = (expectedSize + blockElements - 1) / blockElements
    val availableCores = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
    val blocksPerPartition = (totalBlocks + availableCores - 1) / availableCores

    val rawSourceAddress = sourceSlice.address()
    val rawDestinationAddress = destinationSlice.address()

    val tasks = (0 until availableCores).mapNotNull { partitionIndex ->
        val startBlock = partitionIndex * blocksPerPartition
        if (startBlock >= totalBlocks) return@mapNotNull null

        val startElement = startBlock * blockElements
        val partitionElements = minOf(expectedSize - startElement, blocksPerPartition * blockElements)
        val startWord = startBlock * blockWords
        val availableWords = maxOf(0, longsCount - startWord)
        val neededWords = BlockDataCodec.calculatePackedLongCount(partitionElements, bitsPerEntry, precise)
        val partitionWords = minOf(availableWords, neededWords)

        Callable {
            val chunkSource = MemorySegment.ofAddress(
                rawSourceAddress + (startWord.toLong() * Long.SIZE_BYTES),
            ).reinterpret(partitionWords.toLong() * Long.SIZE_BYTES)

            val chunkDestination = MemorySegment.ofAddress(
                rawDestinationAddress + (startElement.toLong() * Short.SIZE_BYTES),
            ).reinterpret(partitionElements.toLong() * Short.SIZE_BYTES)

            TotemBitPackSys.unpackLongsToShorts(
                chunkSource,
                partitionWords.toLong(),
                chunkDestination,
                partitionElements.toLong(),
                bitsPerEntry,
                precise,
                bigEndianSource = true,
            )
        }
    }

    val futures = TotemThreadPool.executor.invokeAll(tasks)
    for (future in futures) {
        future.get()
    }
}

private fun packNativeParallel(
    sourceSlice: MemorySegment,
    elementCount: Int,
    destinationSlice: MemorySegment,
    bitsPerEntry: Int,
    precise: Boolean,
) {
    val blockElements = if (precise) 64 else (64 / bitsPerEntry)
    val blockWords = if (precise) bitsPerEntry else 1
    val totalBlocks = (elementCount + blockElements - 1) / blockElements
    val availableCores = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
    val blocksPerPartition = (totalBlocks + availableCores - 1) / availableCores

    val rawSourceAddress = sourceSlice.address()
    val rawDestinationAddress = destinationSlice.address()

    val tasks = (0 until availableCores).mapNotNull { partitionIndex ->
        val startBlock = partitionIndex * blocksPerPartition
        if (startBlock >= totalBlocks) return@mapNotNull null

        val startElement = startBlock * blockElements
        val partitionElements = minOf(elementCount - startElement, blocksPerPartition * blockElements)
        val startWord = startBlock * blockWords
        val partitionWords = BlockDataCodec.calculatePackedLongCount(partitionElements, bitsPerEntry, precise)

        Callable {
            val chunkSource = MemorySegment.ofAddress(
                rawSourceAddress + (startElement.toLong() * Short.SIZE_BYTES),
            ).reinterpret(partitionElements.toLong() * Short.SIZE_BYTES)

            val chunkDestination = MemorySegment.ofAddress(
                rawDestinationAddress + (startWord.toLong() * Long.SIZE_BYTES),
            ).reinterpret(partitionWords.toLong() * Long.SIZE_BYTES)

            TotemBitPackSys.packShortsToLongs(
                chunkSource,
                partitionElements.toLong(),
                chunkDestination,
                partitionWords.toLong(),
                bitsPerEntry,
                precise,
                bigEndianDestination = true,
            )
        }
    }

    val futures = TotemThreadPool.executor.invokeAll(tasks)
    for (future in futures) {
        future.get()
    }
}
