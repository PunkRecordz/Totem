package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.ffi.TotemCodecSys
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference
import java.nio.ByteOrder

internal object GzipParallelDecompressor {

    private const val MIN_GZIP_MEMBER_BYTES: Long = 18L
    private const val DIRECT_SINGLE_MEMBER_INPUT_LIMIT_BYTES: Long = 64L * 1024L
    private const val MAX_SPECULATIVE_ALLOCATION_BYTES: Long = 512L * 1024L * 1024L
    private const val MAX_DEFLATE_EXPANSION_RATIO: Long = 1032L

    private val LITTLE_ENDIAN_INT: ValueLayout.OfInt =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    fun decompress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment = decompressInternal(input, targetArena = arena)

    fun decompressTransient(input: MemorySegment): MemorySegment =
        decompressInternal(input, targetArena = null)

    private fun decompressInternal(
        input: MemorySegment,
        targetArena: Arena?,
    ): MemorySegment {
        val inputSize = input.byteSize()
        require(inputSize >= MIN_GZIP_MEMBER_BYTES) {
            "Input GZIP segment size ($inputSize bytes) is shorter than minimum 18-byte GZIP member"
        }

        val scratch = CompressionScratch.get()
        val nativeInput = scratch.nativeSource(input)

        try {
            val trailerUncompressedSize = nativeInput.get(LITTLE_ENDIAN_INT, inputSize - 4L).toLong() and 0xFFFFFFFFL
            val maxSpeculativeBytes = maxSpeculativeBytesFor(inputSize)

            if (
                inputSize <= DIRECT_SINGLE_MEMBER_INPUT_LIMIT_BYTES &&
                trailerUncompressedSize <= minOf(CompressionScratch.MAX_OUTPUT_CAPACITY_BYTES, maxSpeculativeBytes)
            ) {
                val singleMemberSlice = tryDecompressSingleMemberToScratch(
                    nativeInput = nativeInput,
                    trailerUncompressedSize = trailerUncompressedSize,
                    scratch = scratch,
                )
                if (singleMemberSlice != null) {
                    return copyToTargetOrReturnSlice(singleMemberSlice, targetArena)
                }
            }

            return decompressScannedOrStreaming(
                nativeInput = nativeInput,
                targetArena = targetArena,
                scratch = scratch,
                maxSpeculativeBytes = maxSpeculativeBytes,
            )
        } finally {
            Reference.reachabilityFence(input)
            Reference.reachabilityFence(nativeInput)
        }
    }

    private fun tryDecompressSingleMemberToScratch(
        nativeInput: MemorySegment,
        trailerUncompressedSize: Long,
        scratch: CompressionScratch,
    ): MemorySegment? {
        val inputSize = nativeInput.byteSize()
        if (trailerUncompressedSize > scratch.outputCapacity) {
            scratch.ensureOutputCapacity(trailerUncompressedSize)
        }

        while (true) {
            val producedBytes = TotemCodecSys.inflate(
                TotemCodecSys.FORMAT_GZIP,
                nativeInput,
                scratch.outputSegment,
                scratch.consumedBytesSegment,
            )

            when {
                producedBytes >= 0L -> return when (scratch.consumedBytes) {
                    inputSize -> scratch.outputSegment.asSlice(0L, producedBytes)
                    else -> null
                }
                producedBytes == TotemCodecSys.STATUS_INSUFFICIENT_SPACE && scratch.growOutputCapacity() -> continue
                else -> return null
            }
        }
    }

    private fun decompressScannedOrStreaming(
        nativeInput: MemorySegment,
        targetArena: Arena?,
        scratch: CompressionScratch,
        maxSpeculativeBytes: Long,
    ): MemorySegment {
        val inputSize = nativeInput.byteSize()
        val offsetsSegment = scratch.gzipMemberOffsetsSegment
        val memberCount = TotemSys.scanGzipMembers(
            nativeInput,
            offsetsSegment,
            CompressionScratch.MAX_SCANNED_GZIP_MEMBERS,
        )

        val firstOffsetValid = memberCount > 0 && offsetsSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0L) == 0L

        if (firstOffsetValid && memberCount == 1 && inputSize <= Int.MAX_VALUE.toLong()) {
            val expectedSize = nativeInput.get(LITTLE_ENDIAN_INT, inputSize - 4L).toLong() and 0xFFFFFFFFL

            when {
                expectedSize == 0L -> {
                    val producedBytes = TotemCodecSys.inflate(
                        TotemCodecSys.FORMAT_GZIP,
                        nativeInput,
                        scratch.outputSegment,
                        scratch.consumedBytesSegment,
                    )
                    if (producedBytes >= 0L && scratch.consumedBytes == inputSize) {
                        return copyToTargetOrReturnSlice(scratch.outputSegment.asSlice(0L, producedBytes), targetArena)
                    }
                }
                expectedSize <= maxSpeculativeBytes -> {
                    val outputSegment = allocateTarget(expectedSize, targetArena, scratch)
                    val producedBytes = TotemCodecSys.inflate(
                        TotemCodecSys.FORMAT_GZIP,
                        nativeInput,
                        outputSegment,
                        scratch.consumedBytesSegment,
                    )
                    if (producedBytes >= 0L && scratch.consumedBytes == inputSize) {
                        return outputSegment.asSlice(0L, producedBytes)
                    }
                }
            }
        }

        if (firstOffsetValid && memberCount in 2 until CompressionScratch.MAX_SCANNED_GZIP_MEMBERS) {
            val totalUncompressedSize = sumMemberUncompressedSizes(nativeInput, offsetsSegment, memberCount)
            if (totalUncompressedSize in 0L..maxSpeculativeBytes) {
                val outputSegment = allocateTarget(totalUncompressedSize, targetArena, scratch)
                val producedBytes = TotemCodecSys.gzipInflateMembers(
                    source = nativeInput,
                    memberOffsets = offsetsSegment,
                    memberCount = memberCount,
                    destination = outputSegment,
                )

                if (producedBytes == totalUncompressedSize) {
                    return outputSegment
                }
            }
        }

        return GzipStreamFallback.decompressStreaming(nativeInput, targetArena, scratch)
    }

    private fun sumMemberUncompressedSizes(
        nativeInput: MemorySegment,
        offsetsSegment: MemorySegment,
        memberCount: Int,
    ): Long {
        val inputSize = nativeInput.byteSize()
        var totalUncompressedSize = 0L

        for (index in 0 until memberCount) {
            val memberStart = offsetsSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, index.toLong() * Long.SIZE_BYTES)
            val memberEnd = when {
                index + 1 < memberCount ->
                    offsetsSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, (index + 1).toLong() * Long.SIZE_BYTES)
                else -> inputSize
            }

            if (memberStart < 0L || memberEnd > inputSize || memberEnd - memberStart < MIN_GZIP_MEMBER_BYTES) {
                return -1L
            }

            val memberUncompressedSize = nativeInput.get(LITTLE_ENDIAN_INT, memberEnd - 4L).toLong() and 0xFFFFFFFFL
            totalUncompressedSize += memberUncompressedSize
        }

        return totalUncompressedSize
    }

    private fun maxSpeculativeBytesFor(inputSize: Long): Long {
        val theoreticalBound = when {
            inputSize > Long.MAX_VALUE / MAX_DEFLATE_EXPANSION_RATIO -> Long.MAX_VALUE
            else -> inputSize * MAX_DEFLATE_EXPANSION_RATIO
        }

        return minOf(MAX_SPECULATIVE_ALLOCATION_BYTES, theoreticalBound)
    }

    private fun copyToTargetOrReturnSlice(
        scratchSlice: MemorySegment,
        targetArena: Arena?,
    ): MemorySegment {
        if (targetArena == null) {
            return scratchSlice
        }

        val byteCount = scratchSlice.byteSize()
        val exactSegment = targetArena.allocateUninitialized(byteCount)
        if (byteCount > 0L) {
            MemorySegment.copy(scratchSlice, 0L, exactSegment, 0L, byteCount)
        }

        return exactSegment
    }

    private fun allocateTarget(
        byteSize: Long,
        targetArena: Arena?,
        scratch: CompressionScratch,
    ): MemorySegment {
        if (targetArena != null) {
            return targetArena.allocateUninitialized(byteSize)
        }

        return scratch.ensureOutputCapacity(byteSize).asSlice(0L, byteSize)
    }

}
