package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.zip.DataFormatException

internal object GzipStreamFallback {

    private const val MIN_GZIP_MEMBER_BYTES: Long = 18L
    private const val GZIP_HEADER_BYTES: Long = 10L
    private const val GZIP_TRAILER_BYTES: Long = 8L
    private const val RESERVED_HEADER_FLAGS_MASK: Int = 0xE0

    private val LITTLE_ENDIAN_SHORT: ValueLayout.OfShort =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    private val LITTLE_ENDIAN_INT: ValueLayout.OfInt =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    fun decompressStreaming(
        nativeInput: MemorySegment,
        targetArena: Arena?,
        scratch: CompressionScratch,
    ): MemorySegment {
        val inputLimit = nativeInput.byteSize()
        require(inputLimit >= MIN_GZIP_MEMBER_BYTES) {
            "Malformed GZIP stream: input size ($inputLimit bytes) is shorter than minimum 18-byte GZIP member"
        }

        val inflater = scratch.rawInflater
        val crc32 = scratch.crc32
        inflater.reset()

        try {
            var memberStart = 0L
            var totalProduced = 0L
            var activeSegment = scratch.outputSegment

            while (memberStart < inputLimit) {
                val deflateStart = skipStrictGzipHeader(nativeInput, memberStart, inputLimit)
                val memberOutputStart = totalProduced

                inflater.reset()
                crc32.reset()

                val state = DeflateStreamInflater.inflateStream(
                    input = nativeInput,
                    startOffset = deflateStart,
                    endOffset = inputLimit,
                    inflater = inflater,
                    scratch = scratch,
                    initialSegment = activeSegment,
                    initialProducedBytes = totalProduced,
                    crc32 = crc32,
                    formatName = "GZIP",
                )

                activeSegment = state.segment
                totalProduced = state.totalProducedBytes

                val trailerOffset = Math.addExact(deflateStart, inflater.bytesRead)
                if (trailerOffset + GZIP_TRAILER_BYTES > inputLimit) {
                    throw IllegalArgumentException(
                        "Truncated GZIP member trailer at byte offset $trailerOffset (inputLimit=$inputLimit)",
                    )
                }

                val expectedCrc32 = nativeInput.get(LITTLE_ENDIAN_INT, trailerOffset)
                val actualCrc32 = crc32.value.toInt()
                if (expectedCrc32 != actualCrc32) {
                    throw IllegalArgumentException(
                        "Corrupt GZIP member at byte offset $memberStart: CRC32 mismatch " +
                            "(expected=0x${expectedCrc32.toUInt().toString(16)}, actual=0x${actualCrc32.toUInt().toString(16)})",
                    )
                }

                val expectedSizeModulo = nativeInput.get(LITTLE_ENDIAN_INT, trailerOffset + 4L)
                val actualSizeModulo = (totalProduced - memberOutputStart).toInt()
                if (expectedSizeModulo != actualSizeModulo) {
                    throw IllegalArgumentException(
                        "Corrupt GZIP member at byte offset $memberStart: ISIZE mismatch " +
                            "(expected=${expectedSizeModulo.toUInt()}, actual=${actualSizeModulo.toUInt()})",
                    )
                }

                memberStart = trailerOffset + GZIP_TRAILER_BYTES
            }

            val resultSlice = activeSegment.asSlice(0L, totalProduced)
            if (targetArena == null) {
                return resultSlice
            }

            val arenaOutput = targetArena.allocateUninitialized(totalProduced)
            if (totalProduced > 0L) {
                MemorySegment.copy(resultSlice, 0L, arenaOutput, 0L, totalProduced)
            }

            return arenaOutput
        } catch (exception: DataFormatException) {
            throw IllegalArgumentException("Malformed GZIP stream: ${exception.message}", exception)
        } finally {
            inflater.reset()
        }
    }

    private fun skipStrictGzipHeader(
        segment: MemorySegment,
        offset: Long,
        limit: Long,
    ): Long {
        if (offset + MIN_GZIP_MEMBER_BYTES > limit) {
            throw IllegalArgumentException(
                "Truncated or trailing non-GZIP bytes at offset $offset (remaining=${limit - offset})",
            )
        }

        val magicFirst = segment.get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF
        val magicSecond = segment.get(ValueLayout.JAVA_BYTE, offset + 1L).toInt() and 0xFF
        val compressionMethod = segment.get(ValueLayout.JAVA_BYTE, offset + 2L).toInt() and 0xFF
        val flags = segment.get(ValueLayout.JAVA_BYTE, offset + 3L).toInt() and 0xFF

        if (
            magicFirst != 0x1F ||
            magicSecond != 0x8B ||
            compressionMethod != 8 ||
            (flags and RESERVED_HEADER_FLAGS_MASK) != 0
        ) {
            throw IllegalArgumentException("Malformed GZIP header at byte offset $offset")
        }

        var cursor = offset + GZIP_HEADER_BYTES

        if ((flags and 0x04) != 0) {
            if (cursor + 2L > limit - GZIP_TRAILER_BYTES) {
                throw IllegalArgumentException("Truncated GZIP FEXTRA field at byte offset $cursor")
            }
            val extraLength = segment.get(LITTLE_ENDIAN_SHORT, cursor).toInt() and 0xFFFF
            cursor += 2L + extraLength.toLong()
        }

        if ((flags and 0x08) != 0) {
            cursor = skipZeroTerminatedField(segment, cursor, limit - GZIP_TRAILER_BYTES, "FNAME")
        }

        if ((flags and 0x10) != 0) {
            cursor = skipZeroTerminatedField(segment, cursor, limit - GZIP_TRAILER_BYTES, "FCOMMENT")
        }

        if ((flags and 0x02) != 0) {
            cursor += 2L
        }

        if (cursor > limit - GZIP_TRAILER_BYTES) {
            throw IllegalArgumentException("Truncated GZIP header options at byte offset $offset")
        }

        return cursor
    }

    private fun skipZeroTerminatedField(
        segment: MemorySegment,
        startCursor: Long,
        maxCursor: Long,
        fieldName: String,
    ): Long {
        var cursor = startCursor
        while (cursor < maxCursor) {
            if (segment.get(ValueLayout.JAVA_BYTE, cursor) == 0.toByte()) {
                return cursor + 1L
            }
            cursor++
        }

        throw IllegalArgumentException("Unterminated GZIP $fieldName header field starting at byte offset $startCursor")
    }

}
