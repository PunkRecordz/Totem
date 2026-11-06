package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.tag.CompoundTag

fun interface TransientChunkConsumer {

    fun accept(
        chunkX: Int,
        chunkZ: Int,
        rootName: String,
        compound: CompoundTag,
    )

}
