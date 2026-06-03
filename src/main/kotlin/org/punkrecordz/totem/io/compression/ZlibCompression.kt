package org.punkrecordz.totem.io.compression

import com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor
import com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor
import org.punkrecordz.totem.io.allocateUninitialized
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.zip.InflaterInputStream

private val zlibDecompressor = ThreadLocal.withInitial { LibdeflateDecompressor() }
private val zlibCompressors = ThreadLocal.withInitial { HashMap<Int, LibdeflateCompressor>() }

class ZlibCompression(val level: Int = 1) : Compression {

    override fun decompress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputSize = input.byteSize()
        var capacity = maxOf(65536L, inputSize * 4)

        while (true) {
            try {
                val output = arena.allocateUninitialized(capacity)
                val decompressor = zlibDecompressor.get()
                
                val inputBuffer = input.asByteBuffer()
                val outputBuffer = output.asByteBuffer()
                val result = decompressor.zlibDecompressEx(inputBuffer, outputBuffer)
                val decompressedSize = result.outputBytesProduced().toLong()
                
                return output.asSlice(0, decompressedSize)
            } catch (_: Exception) {
                // double capacity if the buffer is too small, fallback if size becomes unreasonably large
                if (capacity >= 16 * 1024 * 1024L) {
                    return decompressRobust(input, arena)
                }
                
                capacity *= 2
            }
        }
    }

    override fun compress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val compressor = zlibCompressors.get().getOrPut(level) {
            LibdeflateCompressor(level)
        }
        
        val bound = compressor.zlibCompressBound(input.byteSize().toInt()).toLong()
        val output = arena.allocateUninitialized(bound)
        
        val inputBuffer = input.asByteBuffer()
        val outputBuffer = output.asByteBuffer()
        val compressedSize = compressor.zlibCompress(inputBuffer, outputBuffer)
        
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
                InflaterInputStream(inputStream).use { inflaterInputStream ->
                    val buffer = ByteArray(65536)
                    var bytesRead = inflaterInputStream.read(buffer)
                    
                    while (bytesRead != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesRead = inflaterInputStream.read(buffer)
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
