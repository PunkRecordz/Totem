package org.punkrecordz.totem.io.compression

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

object NoneCompression : Compression {

    override fun decompress(input: MemorySegment, arena: Arena): MemorySegment = input

    override fun compress(input: MemorySegment, arena: Arena): MemorySegment = input

}
