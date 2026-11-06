package org.punkrecordz.totem.io

import org.punkrecordz.totem.io.memory.NativeSlabPool
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

fun Arena.allocateUninitialized(byteSize: Long): MemorySegment =
    NativeSlabPool.allocate(byteSize, this)
