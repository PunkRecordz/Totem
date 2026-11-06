package org.punkrecordz.totem.anvil

import java.lang.foreign.MemorySegment

fun interface ChunkSliceConsumer {

    fun accept(
        chunkX: Int,
        chunkZ: Int,
        uncompressedNbt: MemorySegment,
    )

}
