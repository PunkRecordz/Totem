package org.punkrecordz.totem.io.compression

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

interface Compression {

    fun decompress(input: MemorySegment, arena: Arena): MemorySegment

    fun compress(input: MemorySegment, arena: Arena): MemorySegment

}
