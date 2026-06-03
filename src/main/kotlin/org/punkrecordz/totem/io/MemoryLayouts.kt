package org.punkrecordz.totem.io

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

object MemoryLayouts {

    val BYTE: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

    val SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED
        .withOrder(ByteOrder.BIG_ENDIAN)

    val INT: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED
        .withOrder(ByteOrder.BIG_ENDIAN)

    val LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED
        .withOrder(ByteOrder.BIG_ENDIAN)

    val FLOAT: ValueLayout.OfFloat = ValueLayout.JAVA_FLOAT_UNALIGNED
        .withOrder(ByteOrder.BIG_ENDIAN)

    val DOUBLE: ValueLayout.OfDouble = ValueLayout.JAVA_DOUBLE_UNALIGNED
        .withOrder(ByteOrder.BIG_ENDIAN)

    fun readString(segment: MemorySegment, offset: Long = 0L): String {
        val length = segment.get(SHORT, offset).toInt() and 0xFFFF
        if (length == 0) {
            return ""
        }

        val bytes = segment.asSlice(offset + 2L, length.toLong()).toArray(BYTE)
        return String(bytes, Charsets.UTF_8)
    }

}
