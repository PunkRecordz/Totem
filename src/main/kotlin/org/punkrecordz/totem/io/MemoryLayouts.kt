package org.punkrecordz.totem.io

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

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
        if (length == 0) return ""

        val bytes = ByteArray(length)
        MemorySegment.copy(segment, BYTE, offset + 2L, bytes, 0, length)
        return decodeString(bytes)
    }

    fun decodeString(bytes: ByteArray): String {
        val length = bytes.size
        if (length == 0) return ""

        var isPureAscii = true
        for (index in 0 until length) {
            if (bytes[index] <= 0) {
                isPureAscii = false
                break
            }
        }

        if (isPureAscii) {
            return String(bytes, StandardCharsets.ISO_8859_1)
        }

        val characters = CharArray(length)
        var byteIndex = 0
        var characterIndex = 0

        while (byteIndex < length) {
            val leadingByte = bytes[byteIndex++].toInt() and 0xFF

            when (leadingByte shr 4) {
                in 0..7 -> characters[characterIndex++] = leadingByte.toChar()
                12, 13 -> {
                    if (byteIndex >= length) {
                        throw IllegalArgumentException("Malformed Modified UTF-8: truncated 2-byte sequence at index $byteIndex")
                    }
                    val trailingByte = bytes[byteIndex++].toInt()
                    if ((trailingByte and 0xC0) != 0x80) {
                        throw IllegalArgumentException("Malformed Modified UTF-8: invalid continuation byte at index ${byteIndex - 1}")
                    }
                    characters[characterIndex++] = (((leadingByte and 0x1F) shl 6) or (trailingByte and 0x3F)).toChar()
                }
                14 -> {
                    if (byteIndex + 1 >= length) {
                        throw IllegalArgumentException("Malformed Modified UTF-8: truncated 3-byte sequence at index $byteIndex")
                    }
                    val secondByte = bytes[byteIndex++].toInt()
                    val thirdByte = bytes[byteIndex++].toInt()
                    if ((secondByte and 0xC0) != 0x80 || (thirdByte and 0xC0) != 0x80) {
                        throw IllegalArgumentException("Malformed Modified UTF-8: invalid continuation byte at index ${byteIndex - 2}")
                    }
                    characters[characterIndex++] = (((leadingByte and 0x0F) shl 12) or ((secondByte and 0x3F) shl 6) or (thirdByte and 0x3F)).toChar()
                }
                else -> throw IllegalArgumentException("Malformed Modified UTF-8 at index ${byteIndex - 1}")
            }
        }

        return String(characters, 0, characterIndex)
    }

    fun isAsciiWithoutNull(value: String): Boolean {
        for (element in value) {
            val codePoint = element.code
            if (codePoint == 0 || codePoint >= 128) {
                return false
            }
        }
        return true
    }

    fun encodeString(value: String): ByteArray {
        val characterLength = value.length
        if (characterLength > 65535) {
            throw IllegalArgumentException("String character length ($characterLength) exceeds maximum size of 65535 bytes")
        }

        val byteLength = stringByteLength(value)
        val result = ByteArray(byteLength)

        if (byteLength == characterLength) {
            // fast path for pure ascii strings
            for (characterIndex in 0 until characterLength) {
                result[characterIndex] = value[characterIndex].code.toByte()
            }

            return result
        }

        // encode modified utf-8 directly without stream wrapper overhead
        var byteIndex = 0
        for (characterIndex in 0 until characterLength) {
            val codePoint = value[characterIndex].code
            when {
                codePoint in 1..0x007F -> {
                    result[byteIndex++] = codePoint.toByte()
                }
                codePoint == 0 || codePoint in 0x0080..0x07FF -> {
                    result[byteIndex++] = (0xC0 or ((codePoint shr 6) and 0x1F)).toByte()
                    result[byteIndex++] = (0x80 or (codePoint and 0x3F)).toByte()
                }
                else -> {
                    result[byteIndex++] = (0xE0 or ((codePoint shr 12) and 0x0F)).toByte()
                    result[byteIndex++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                    result[byteIndex++] = (0x80 or (codePoint and 0x3F)).toByte()
                }
            }
        }

        return result
    }

    fun stringByteLength(value: String): Int {
        val characterLength = value.length
        if (characterLength > 65535) {
            throw IllegalArgumentException("String character length ($characterLength) exceeds maximum size of 65535 bytes")
        }

        var byteLength = characterLength
        for (characterIndex in 0 until characterLength) {
            val codePoint = value[characterIndex].code
            when {
                codePoint == 0 || codePoint in 0x0080..0x07FF -> byteLength += 1
                codePoint >= 0x0800 -> byteLength += 2
            }
        }

        if (byteLength > 65535) {
            throw IllegalArgumentException("Encoded string length ($byteLength bytes) exceeds maximum size of 65535 bytes")
        }

        return byteLength
    }

    fun writeString(segment: MemorySegment, offset: Long, value: String): Long {
        val characterLength = value.length
        if (characterLength > 65535) {
            throw IllegalArgumentException("String character length ($characterLength) exceeds maximum size of 65535 bytes")
        }

        val shortSize = SHORT.byteSize()
        val dataOffset = offset + shortSize

        // fast single-pass path: check ascii and write bytes directly off-heap without heap allocation
        var isPureAscii = true
        for (characterIndex in 0 until characterLength) {
            val codePoint = value[characterIndex].code
            if (codePoint == 0 || codePoint >= 128) {
                isPureAscii = false
                break
            }

            segment.set(BYTE, dataOffset + characterIndex.toLong(), codePoint.toByte())
        }

        if (isPureAscii) {
            segment.set(SHORT, offset, characterLength.toShort())
            return shortSize + characterLength
        }

        // non-ascii fallback: modified utf-8 encoding
        val encodedBytes = encodeString(value)
        segment.set(SHORT, offset, encodedBytes.size.toShort())
        MemorySegment.copy(
            encodedBytes,
            0,
            segment,
            BYTE,
            dataOffset,
            encodedBytes.size,
        )

        return shortSize + encodedBytes.size
    }

}
