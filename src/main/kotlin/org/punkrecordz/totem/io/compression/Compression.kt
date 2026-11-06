package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

interface Compression {

    fun decompress(input: MemorySegment, arena: Arena): MemorySegment

    fun decompressTransient(input: MemorySegment): MemorySegment = decompress(input, Arena.ofAuto())

    fun compress(input: MemorySegment, arena: Arena): MemorySegment

    fun compress(slices: List<MemorySegment>, arena: Arena): MemorySegment {
        if (slices.isEmpty()) {
            return arena.allocate(0L)
        }

        if (slices.size == 1) {
            return compress(slices[0], arena)
        }

        val totalSize = slices.sumOf { slice -> slice.byteSize() }
        if (totalSize <= CompressionScratch.MAX_OUTPUT_CAPACITY_BYTES) {
            val scratch = CompressionScratch.get()
            val destinationSegment = scratch.stageHeapInput(totalSize)
            var currentOffset = 0L

            for (slice in slices) {
                val sliceSize = slice.byteSize()
                if (sliceSize > 0L) {
                    MemorySegment.copy(slice, 0L, destinationSegment, currentOffset, sliceSize)
                    currentOffset += sliceSize
                }
            }

            return compress(destinationSegment.asSlice(0L, totalSize), arena)
        }

        return Arena.ofConfined().use { temporaryArena ->
            val contiguousSegment = temporaryArena.allocateUninitialized(totalSize)
            var currentOffset = 0L

            for (slice in slices) {
                MemorySegment.copy(slice, 0L, contiguousSegment, currentOffset, slice.byteSize())
                currentOffset += slice.byteSize()
            }

            compress(contiguousSegment, arena)
        }
    }

}
