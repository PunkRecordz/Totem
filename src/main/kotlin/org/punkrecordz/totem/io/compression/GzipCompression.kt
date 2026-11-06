package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.ffi.TotemCodecSys
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.memory.OffHeapScratchBuffer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.ref.Reference

private const val PARALLEL_THRESHOLD_BYTES: Long = 256L * 1024L

class GzipCompression(val level: Int = 1) : Compression {

    override fun decompress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment = GzipParallelDecompressor.decompress(input, arena)

    override fun decompressTransient(input: MemorySegment): MemorySegment =
        GzipParallelDecompressor.decompressTransient(input)

    override fun compress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputSize = input.byteSize()
        if (TotemCodecSys.nativeParallelism <= 1 || inputSize < PARALLEL_THRESHOLD_BYTES) {
            return compressSequential(input, arena)
        }

        val scratch = CompressionScratch.get()
        val nativeInput = scratch.nativeSource(input)
        val descriptors = scratch.prepareSingleDescriptor(nativeInput)

        return try {
            compressDescriptorsNative(descriptors, descriptorCount = 1, arena = arena)
        } finally {
            Reference.reachabilityFence(input)
            Reference.reachabilityFence(nativeInput)
            Reference.reachabilityFence(descriptors)
        }
    }

    override fun compress(
        slices: List<MemorySegment>,
        arena: Arena,
    ): MemorySegment {
        if (slices.isEmpty()) {
            return arena.allocate(0L)
        }

        if (slices.size == 1) {
            return compress(slices[0], arena)
        }

        val scratch = CompressionScratch.get()

        return GzipSliceStaging.withStagedDescriptors(slices, scratch) { descriptors, descriptorCount ->
            when (descriptorCount) {
                0 -> arena.allocate(0L)
                else -> compressDescriptorsNative(descriptors, descriptorCount, arena)
            }
        }
    }

    private fun compressSequential(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputSize = input.byteSize()
        require(inputSize <= Int.MAX_VALUE.toLong()) {
            "GZIP sequential compression size ($inputSize bytes) exceeds Int.MAX_VALUE"
        }

        val scratch = CompressionScratch.get()
        val sourceSegment = scratch.nativeSource(input)
        val compressBound = TotemCodecSys.deflateBound(TotemCodecSys.FORMAT_GZIP, inputSize)
        val destinationSegment = scratch.ensureOutputCapacity(compressBound).asSlice(0L, compressBound)

        val compressedBytes = try {
            TotemCodecSys.deflate(TotemCodecSys.FORMAT_GZIP, level, sourceSegment, destinationSegment)
        } finally {
            Reference.reachabilityFence(input)
            Reference.reachabilityFence(sourceSegment)
        }

        if (compressedBytes <= 0L) {
            throw IllegalStateException(
                "Failed to GZIP-compress segment of $inputSize bytes with bound $compressBound (native status $compressedBytes)",
            )
        }

        val outputSegment = arena.allocateUninitialized(compressedBytes)
        MemorySegment.copy(destinationSegment, 0L, outputSegment, 0L, compressedBytes)

        return outputSegment
    }

    private fun compressDescriptorsNative(
        descriptors: MemorySegment,
        descriptorCount: Int,
        arena: Arena,
    ): MemorySegment {
        val totalBound = TotemCodecSys.gzipSlicesBound(descriptors, descriptorCount)
        if (totalBound <= 0L) {
            throw IllegalStateException("Invalid GZIP slice bound $totalBound for $descriptorCount slices")
        }

        val stagingSegment = OffHeapScratchBuffer.getCompressionBuffer().acquire(totalBound)
        val compressedBytes = try {
            TotemCodecSys.gzipCompressSlices(
                level = level,
                descriptors = descriptors,
                sliceCount = descriptorCount,
                staging = stagingSegment,
            )
        } finally {
            Reference.reachabilityFence(stagingSegment)
        }

        if (compressedBytes <= 0L) {
            throw IllegalStateException(
                "Failed to GZIP-compress $descriptorCount slices with bound $totalBound (native status $compressedBytes)",
            )
        }

        val outputSegment = arena.allocateUninitialized(compressedBytes)
        MemorySegment.copy(stagingSegment, 0L, outputSegment, 0L, compressedBytes)

        return outputSegment
    }

}
