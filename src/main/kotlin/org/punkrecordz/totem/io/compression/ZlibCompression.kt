package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.ffi.TotemCodecSys
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.ref.Reference
import java.util.zip.DataFormatException

class ZlibCompression(val level: Int = 1) : Compression {

    override fun decompress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val transientSlice = decompressTransient(input)
        val decompressedBytes = transientSlice.byteSize()
        val outputSegment = arena.allocateUninitialized(decompressedBytes)

        if (decompressedBytes > 0L) {
            MemorySegment.copy(transientSlice, 0L, outputSegment, 0L, decompressedBytes)
        }

        return outputSegment
    }

    override fun decompressTransient(input: MemorySegment): MemorySegment {
        val inputSize = input.byteSize()
        require(inputSize >= 2L) {
            "Input ZLIB segment size ($inputSize bytes) is too short to contain a valid ZLIB header"
        }

        val scratch = CompressionScratch.get()
        val nativeInput = scratch.nativeSource(input)

        try {
            while (true) {
                val producedBytes = TotemCodecSys.inflate(
                    TotemCodecSys.FORMAT_ZLIB,
                    nativeInput,
                    scratch.outputSegment,
                )

                when {
                    producedBytes >= 0L -> return scratch.outputSegment.asSlice(0L, producedBytes)
                    producedBytes == TotemCodecSys.STATUS_INSUFFICIENT_SPACE && scratch.growOutputCapacity() -> continue
                    producedBytes == TotemCodecSys.STATUS_INSUFFICIENT_SPACE -> return decompressStreaming(nativeInput, scratch)
                    else -> throw IllegalArgumentException(
                        "Malformed ZLIB stream of $inputSize bytes (native status $producedBytes)",
                    )
                }
            }
        } finally {
            Reference.reachabilityFence(input)
            Reference.reachabilityFence(nativeInput)
        }
    }

    override fun compress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputSize = input.byteSize()
        require(inputSize <= Int.MAX_VALUE.toLong()) {
            "ZLIB single-segment compression size ($inputSize bytes) exceeds Int.MAX_VALUE"
        }

        val scratch = CompressionScratch.get()
        val nativeInput = scratch.nativeSource(input)
        val compressBound = TotemCodecSys.deflateBound(TotemCodecSys.FORMAT_ZLIB, inputSize)
        val destinationSegment = scratch.ensureOutputCapacity(compressBound).asSlice(0L, compressBound)

        val producedBytes = try {
            TotemCodecSys.deflate(TotemCodecSys.FORMAT_ZLIB, level, nativeInput, destinationSegment)
        } finally {
            Reference.reachabilityFence(input)
            Reference.reachabilityFence(nativeInput)
        }

        if (producedBytes <= 0L) {
            throw IllegalStateException(
                "Failed to ZLIB-compress segment of $inputSize bytes with bound $compressBound (native status $producedBytes)",
            )
        }

        val outputSegment = arena.allocateUninitialized(producedBytes)
        MemorySegment.copy(destinationSegment, 0L, outputSegment, 0L, producedBytes)

        return outputSegment
    }

    private fun decompressStreaming(
        nativeInput: MemorySegment,
        scratch: CompressionScratch,
    ): MemorySegment {
        val inflater = scratch.zlibInflater
        inflater.reset()

        return try {
            val state = DeflateStreamInflater.inflateStream(
                input = nativeInput,
                startOffset = 0L,
                endOffset = nativeInput.byteSize(),
                inflater = inflater,
                scratch = scratch,
                initialSegment = scratch.outputSegment,
                initialProducedBytes = 0L,
                crc32 = null,
                formatName = "ZLIB",
            )

            state.segment.asSlice(0L, state.totalProducedBytes)
        } catch (exception: DataFormatException) {
            throw IllegalArgumentException("Malformed ZLIB stream: ${exception.message}", exception)
        } finally {
            inflater.reset()
        }
    }

}
