package org.punkrecordz.totem.io.compression

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

interface Compression {

    fun decompress(input: MemorySegment, arena: Arena): MemorySegment

    fun compress(input: MemorySegment, arena: Arena): MemorySegment

    fun compress(slices: List<MemorySegment>, arena: Arena): MemorySegment {
        if (slices.isEmpty()) {
            return arena.allocate(0L)
        }

        if (slices.size == 1) {
            return compress(slices[0], arena)
        }

        val totalSize = slices.sumOf { slice -> slice.byteSize() }
        return Arena.ofConfined().use { temporaryArena ->
            val contiguousSegment = temporaryArena.allocate(totalSize)
            var currentOffset = 0L

            for (slice in slices) {
                MemorySegment.copy(slice, 0L, contiguousSegment, currentOffset, slice.byteSize())
                currentOffset += slice.byteSize()
            }

            compress(contiguousSegment, arena)
        }
    }

}
