package org.punkrecordz.totem.io.compression

import com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor
import com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor
import org.punkrecordz.totem.io.allocateUninitialized
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

private val gzipDecompressor = ThreadLocal.withInitial { LibdeflateDecompressor() }
private val gzipCompressors = ThreadLocal.withInitial { HashMap<Int, LibdeflateCompressor>() }

class GzipCompression(val level: Int = 1) : Compression {

    override fun decompress(
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

        if (uncompressedSize >= Int.MAX_VALUE) {
            return decompressRobust(input, arena)
        }

        try {
            val output = arena.allocateUninitialized(uncompressedSize)
            val decompressor = gzipDecompressor.get()

            val inputBuffer = input.asByteBuffer()
            val outputBuffer = output.asByteBuffer()

            try {
                decompressor.gzipDecompress(inputBuffer, outputBuffer, uncompressedSize.toInt())

                return output.asSlice(0, uncompressedSize)
            } catch (exception: Exception) {
                if (exception is EOFException) {
                    return output.asSlice(0, outputBuffer.position().toLong())
                }

                throw IllegalStateException("GZIP decompression failed: ${exception.message}", exception)
            }
        } catch (_: Exception) {
            return decompressRobust(input, arena)
        }
    }

    override fun compress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val compressor = gzipCompressors.get().getOrPut(level) {
            LibdeflateCompressor(level)
        }

        val bound = compressor.gzipCompressBound(input.byteSize().toInt()).toLong()
        val output = arena.allocateUninitialized(bound)

        val inputBuffer = input.asByteBuffer()
        val outputBuffer = output.asByteBuffer()
        val compressedSize = compressor.gzipCompress(inputBuffer, outputBuffer)

        return output.asSlice(0, compressedSize.toLong())
    }

    private fun decompressRobust(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputBuffer = input.asByteBuffer()
        val inputStream = ByteBufferInputStream(inputBuffer)

        ByteArrayOutputStream().use { outputStream ->
            try {
                GZIPInputStream(inputStream).use { gzipInputStream ->
                    // 64kb standard buffer size for robust, sequential gzip stream decompression
                    val buffer = ByteArray(65536)
                    var bytesRead = gzipInputStream.read(buffer)

                    while (bytesRead != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesRead = gzipInputStream.read(buffer)
                    }
                }
            } catch (_: EOFException) {
            }

            val decompressedBytes = outputStream.toByteArray()
            val outputSegment = arena.allocateUninitialized(decompressedBytes.size.toLong())

            MemorySegment.copy(
                decompressedBytes,
                0,
                outputSegment,
                ValueLayout.JAVA_BYTE,
                0,
                decompressedBytes.size,
            )

            return outputSegment
        }
    }

}

internal class ByteBufferInputStream(
    private val byteBuffer: ByteBuffer,
) : InputStream() {

    override fun read(): Int {
        if (!byteBuffer.hasRemaining()) {
            return -1
        }

        return byteBuffer.get().toInt() and 0xFF
    }

    override fun read(
        byteArray: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (!byteBuffer.hasRemaining()) {
            return -1
        }

        val bytesToRead = minOf(length, byteBuffer.remaining())

        byteBuffer.get(byteArray, offset, bytesToRead)

        return bytesToRead
    }

}
