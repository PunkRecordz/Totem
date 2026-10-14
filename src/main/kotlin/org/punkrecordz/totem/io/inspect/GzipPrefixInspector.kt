package org.punkrecordz.totem.io.inspect

import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.memory.OffHeapScratchBuffer
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

internal class GzipMemberIndex(
    val memberCompressedOffsets: LongArray,
    val memberUncompressedOffsets: LongArray,
    val totalUncompressedSize: Long,
)

internal object GzipPrefixInspector {

    private val rawGzipInflater = ThreadLocal.withInitial { Inflater(true) }

    fun inflateGzipPrefixOffHeap(
        inputSegment: MemorySegment,
        startCompressedOffset: Long,
        outputSegment: MemorySegment,
        outputOffset: Long,
        maxBytesToWrite: Long,
        initialBytesToSkip: Long = 0L,
    ): Long {
        if (maxBytesToWrite <= 0L) {
            return 0L
        }

        val inflater = rawGzipInflater.get()
        val inputLimit = inputSegment.byteSize()
        var currentMemberStart = startCompressedOffset
        var remainingToSkip = initialBytesToSkip
        var totalWritten = 0L
        val skipScratchSegment = if (remainingToSkip > 0L) {
            OffHeapScratchBuffer.getCompressionBuffer().acquire(64 * 1024L)
        } else {
            null
        }

        while (totalWritten < maxBytesToWrite && currentMemberStart + 6L <= inputLimit) {
            val deflateStart = skipGzipHeader(inputSegment, currentMemberStart, inputLimit)
            if (deflateStart < 0L) {
                break
            }

            inflater.reset()
            var compressedCursor = deflateStart

            while ((remainingToSkip > 0L || totalWritten < maxBytesToWrite) && !inflater.finished()) {
                if (inflater.needsInput()) {
                    val remainingCompressed = inputLimit - compressedCursor
                    if (remainingCompressed <= 0L) {
                        break
                    }

                    val chunkLength = minOf(remainingCompressed, 16L * 1024 * 1024)
                    val inputBuffer = inputSegment.asSlice(compressedCursor, chunkLength).asByteBuffer()
                    inflater.setInput(inputBuffer)
                    compressedCursor += chunkLength
                }

                if (remainingToSkip > 0L && skipScratchSegment != null) {
                    val skipChunk = minOf(remainingToSkip, skipScratchSegment.byteSize())
                    val skipBuffer = skipScratchSegment.asSlice(0L, skipChunk).asByteBuffer()
                    val skipped = inflater.inflate(skipBuffer)
                    remainingToSkip -= skipped.toLong()
                    if (skipped == 0 && (inflater.needsDictionary() || (!inflater.finished() && !inflater.needsInput()))) {
                        break
                    }
                } else {
                    val targetBuffer = outputSegment.asSlice(
                        outputOffset + totalWritten,
                        maxBytesToWrite - totalWritten,
                    ).asByteBuffer()

                    val bytesInflated = inflater.inflate(targetBuffer)
                    totalWritten += bytesInflated.toLong()

                    if (bytesInflated == 0 && (inflater.needsDictionary() || (!inflater.finished() && !inflater.needsInput()))) {
                        break
                    }
                }
            }

            if (!inflater.finished()) {
                break
            }

            val nextMemberStart = deflateStart + inflater.bytesRead + 8L
            if (nextMemberStart <= currentMemberStart || nextMemberStart + 18L > inputLimit) {
                break
            }

            val nextId1 = inputSegment.get(ValueLayout.JAVA_BYTE, nextMemberStart).toInt() and 0xFF
            val nextId2 = inputSegment.get(ValueLayout.JAVA_BYTE, nextMemberStart + 1L).toInt() and 0xFF
            if (nextId1 != 0x1F || nextId2 != 0x8B) {
                break
            }

            currentMemberStart = nextMemberStart
        }

        return totalWritten
    }

    fun skipGzipHeader(
        segment: MemorySegment,
        offset: Long,
        limit: Long,
    ): Long {
        if (offset + 2L > limit) {
            return -1L
        }

        val id1 = segment.get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF
        val id2 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L).toInt() and 0xFF
        val magic = (id1 shl 8) or id2

        if (magic == 0x7801 || magic == 0x785E || magic == 0x789C || magic == 0x78DA) {
            return offset + 2L
        }

        if (offset + 10L > limit) {
            return -1L
        }

        val compressionMethod = segment.get(ValueLayout.JAVA_BYTE, offset + 2L).toInt() and 0xFF

        if (id1 != 0x1F || id2 != 0x8B || compressionMethod != 8) {
            return -1L
        }

        val flags = segment.get(ValueLayout.JAVA_BYTE, offset + 3L).toInt() and 0xFF
        var cursor = offset + 10L

        if ((flags and 0x04) != 0) {
            if (cursor + 2L > limit) return -1L
            val extraLength = segment.get(
                ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                cursor,
            ).toInt() and 0xFFFF
            cursor += 2L + extraLength
            if (cursor > limit) return -1L
        }

        if ((flags and 0x08) != 0) {
            while (cursor < limit && segment.get(ValueLayout.JAVA_BYTE, cursor) != 0.toByte()) {
                cursor++
            }
            cursor++
            if (cursor > limit) return -1L
        }

        if ((flags and 0x10) != 0) {
            while (cursor < limit && segment.get(ValueLayout.JAVA_BYTE, cursor) != 0.toByte()) {
                cursor++
            }
            cursor++
            if (cursor > limit) return -1L
        }

        if ((flags and 0x02) != 0) {
            cursor += 2L
            if (cursor > limit) return -1L
        }

        return cursor
    }

    fun buildGzipMemberIndex(inputSegment: MemorySegment): GzipMemberIndex? {
        val inputSize = inputSegment.byteSize()
        if (inputSize < 18L) {
            return null
        }

        val lastMemberUncompressedSize = inputSegment.get(
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
            inputSize - 4L,
        ).toLong() and 0xFFFFFFFFL

        // individual members in multi-member streams are <= 16 mb; larger trailers indicate a monolithic stream
        if (lastMemberUncompressedSize > 16 * 1024 * 1024L) {
            return null
        }

        val compressedOffsets = TotemSys.scanGzipMemberOffsets(inputSegment)
        val memberCount = compressedOffsets.size
        if (memberCount <= 1) {
            return null
        }

        val uncompressedOffsets = LongArray(memberCount)
        var cumulativeUncompressed = 0L

        for (index in 0 until memberCount) {
            uncompressedOffsets[index] = cumulativeUncompressed
            val memberEnd = if (index + 1 < memberCount) compressedOffsets[index + 1] else inputSize
            val memberUncompressedSize = inputSegment.get(
                ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                memberEnd - 4L,
            ).toLong() and 0xFFFFFFFFL
            cumulativeUncompressed += memberUncompressedSize
        }

        return GzipMemberIndex(compressedOffsets, uncompressedOffsets, cumulativeUncompressed)
    }

    fun readChannelSlice(
        channel: FileChannel,
        fileOffset: Long,
        targetSlice: MemorySegment,
    ): Long {
        val byteBuffer = targetSlice.asByteBuffer()
        while (byteBuffer.hasRemaining()) {
            val bytesRead = channel.read(byteBuffer, fileOffset + byteBuffer.position().toLong())
            if (bytesRead <= 0) {
                break
            }
        }

        return byteBuffer.position().toLong()
    }

}
