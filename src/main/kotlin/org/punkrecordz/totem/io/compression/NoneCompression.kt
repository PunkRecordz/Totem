package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

object NoneCompression : Compression {

    override fun decompress(input: MemorySegment, arena: Arena): MemorySegment {
        if (input.isNative) {
            return input
        }

        val byteSize = input.byteSize()
        val nativeSegment = arena.allocateUninitialized(byteSize)
        MemorySegment.copy(input, 0L, nativeSegment, 0L, byteSize)

        return nativeSegment
    }

    override fun decompressTransient(input: MemorySegment): MemorySegment {
        if (input.isNative) {
            return input
        }

        val byteSize = input.byteSize()
        val scratchSegment = CompressionScratch.get().ensureOutputCapacity(byteSize)
        if (byteSize > 0L) {
            MemorySegment.copy(input, 0L, scratchSegment, 0L, byteSize)
        }

        return scratchSegment.asSlice(0L, byteSize)
    }

    override fun compress(input: MemorySegment, arena: Arena): MemorySegment = input

}
