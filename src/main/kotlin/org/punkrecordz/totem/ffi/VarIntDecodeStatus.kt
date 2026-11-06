package org.punkrecordz.totem.ffi

internal object VarIntDecodeStatus {

    fun verify(
        status: Long,
        expectedSize: Int,
    ): Long {
        if (status >= 0L) {
            return status
        }

        when (status) {
            -1L -> throw IllegalArgumentException(
                "Malformed VarInt data: byte buffer was truncated in the middle of a VarInt sequence.",
            )
            -2L -> throw IllegalArgumentException(
                "Incomplete VarInt data: expected $expectedSize elements, but buffer contained fewer VarInt values.",
            )
            else -> throw IllegalArgumentException(
                "Failed to decode VarInt data: native decoder returned error code $status.",
            )
        }
    }

}
