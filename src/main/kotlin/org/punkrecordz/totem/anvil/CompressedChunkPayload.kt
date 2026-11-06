package org.punkrecordz.totem.anvil

import java.lang.foreign.MemorySegment

class CompressedChunkPayload(
    val compressionCode: Int,
    val payload: MemorySegment,
) {

    init {
        require(compressionCode in COMPRESSION_GZIP..COMPRESSION_NONE) {
            "Anvil compression code must be $COMPRESSION_GZIP (GZIP), $COMPRESSION_ZLIB (ZLIB) or $COMPRESSION_NONE (NONE), but was $compressionCode"
        }
    }

    companion object {

        const val COMPRESSION_GZIP: Int = 1
        const val COMPRESSION_ZLIB: Int = 2
        const val COMPRESSION_NONE: Int = 3

    }

}
