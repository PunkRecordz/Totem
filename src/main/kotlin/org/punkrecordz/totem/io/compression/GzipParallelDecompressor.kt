package org.punkrecordz.totem.io.compression

import com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor
import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.ByteBufferInputStream
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.stream.NativeByteArrayOutputStream
import java.io.EOFException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.zip.GZIPInputStream

internal object GzipParallelDecompressor {

    private val gzipDecompressor = ThreadLocal.withInitial { LibdeflateDecompressor() }

    fun decompress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputSize = input.byteSize()

        require(inputSize >= 8L) {
            "Input GZIP segment size ($inputSize) is too short to contain a valid GZIP trailer"
        }

        val uncompressedSize = input.get(
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
            inputSize - 4L,
        ).toLong() and 0xFFFFFFFFL

        if (uncompressedSize < Int.MAX_VALUE) {
            try {
                val output = arena.allocateUninitialized(uncompressedSize)
                val decompressor = gzipDecompressor.get()

                val inputBuffer = input.asByteBuffer()
                val outputBuffer = output.asByteBuffer()

                try {
                    decompressor.gzipDecompress(inputBuffer, outputBuffer, uncompressedSize.toInt())

                    if (!inputBuffer.hasRemaining()) {
                        return output.asSlice(0L, uncompressedSize)
                    }
                } catch (exception: Exception) {
                    if (exception is EOFException) {
                        return output.asSlice(0L, outputBuffer.position().toLong())
                    }
                }
            } catch (_: Exception) {
            }
        }

        return decompressMultiMember(input, arena)
    }

    private fun decompressMultiMember(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        try {
            val memberOffsets = TotemSys.scanGzipMemberOffsets(input, 1024)
            val memberCount = memberOffsets.size

            if (memberCount <= 1) {
                return decompressRobust(input, arena)
            }

            val inputSize = input.byteSize()
            val memberSizes = LongArray(memberCount)
            var totalUncompressedSize = 0L

            for (index in 0 until memberCount) {
                val memberEndOffset = if (index + 1 < memberCount) memberOffsets[index + 1] else inputSize
                val uncompressedMemberSize = input.get(
                    ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                    memberEndOffset - 4L,
                ).toLong() and 0xFFFFFFFFL

                memberSizes[index] = uncompressedMemberSize
                totalUncompressedSize += uncompressedMemberSize
            }

            val output = arena.allocateUninitialized(totalUncompressedSize)
            val memberDestinationOffsets = LongArray(memberCount)
            var currentDestinationOffset = 0L

            for (index in 0 until memberCount) {
                memberDestinationOffsets[index] = currentDestinationOffset
                currentDestinationOffset += memberSizes[index]
            }

            val tasks = (0 until memberCount).map { index ->
                val start = memberOffsets[index]
                val end = if (index + 1 < memberCount) memberOffsets[index + 1] else inputSize
                val inputLength = end - start
                val outputOffset = memberDestinationOffsets[index]
                val outputSize = memberSizes[index]

                Callable {
                    val decompressor = gzipDecompressor.get()
                    val inputSlice = input.asSlice(start, inputLength)
                    val outputSlice = output.asSlice(outputOffset, outputSize)

                    decompressor.gzipDecompress(
                        inputSlice.asByteBuffer(),
                        outputSlice.asByteBuffer(),
                        outputSize.toInt(),
                    )
                }
            }

            val futures = TotemThreadPool.executor.invokeAll(tasks)
            for (future in futures) {
                future.get()
            }

            return output
        } catch (_: Exception) {
            return decompressRobust(input, arena)
        }
    }

    private fun decompressRobust(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputBuffer = input.asByteBuffer()
        val inputStream = ByteBufferInputStream(inputBuffer)
        val outputStream = NativeByteArrayOutputStream(arena)

        try {
            GZIPInputStream(inputStream).use { gzipInputStream ->
                val buffer = ByteArray(65536)
                var bytesRead = gzipInputStream.read(buffer)

                while (bytesRead != -1) {
                    outputStream.writeByteArray(buffer, 0, bytesRead)
                    bytesRead = gzipInputStream.read(buffer)
                }
            }
        } catch (_: EOFException) {
        }

        return outputStream.toContiguousSegment(arena)
    }

}
