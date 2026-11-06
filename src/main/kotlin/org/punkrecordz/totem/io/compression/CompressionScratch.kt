package org.punkrecordz.totem.io.compression

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

internal class CompressionScratch {

    val zlibInflater: Inflater = Inflater(false)
    val rawInflater: Inflater = Inflater(true)
    val crc32: CRC32 = CRC32()

    val consumedBytesSegment: MemorySegment = allocateAligned(ValueLayout.JAVA_LONG.byteSize())
    val gzipMemberOffsetsSegment: MemorySegment =
        allocateAligned(MAX_SCANNED_GZIP_MEMBERS.toLong() * ValueLayout.JAVA_LONG.byteSize())

    private val streamInputSegment: MemorySegment = allocateAligned(STREAM_WINDOW_BYTES.toLong())
    private val streamInputByteBuffer: ByteBuffer = streamInputSegment.asByteBuffer()

    val streamWindowSegment: MemorySegment = allocateAligned(STREAM_WINDOW_BYTES.toLong())
    private val streamWindowByteBuffer: ByteBuffer = streamWindowSegment.asByteBuffer()

    private var sliceDescriptorsSegment: MemorySegment =
        allocateAligned(INITIAL_DESCRIPTOR_CAPACITY.toLong() * DESCRIPTOR_STRIDE_BYTES)

    var outputSegment: MemorySegment = allocateAligned(INITIAL_OUTPUT_CAPACITY_BYTES)
        private set

    var inputSegment: MemorySegment = allocateAligned(INITIAL_INPUT_CAPACITY_BYTES)
        private set

    val outputCapacity: Long
        get() = outputSegment.byteSize()

    val consumedBytes: Long
        get() = consumedBytesSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0L)

    fun ensureOutputCapacity(
        requiredBytes: Long,
        preservePrefixBytes: Long = 0L,
    ): MemorySegment {
        require(requiredBytes >= 0L) {
            "Required scratch capacity must be non-negative, but was $requiredBytes"
        }

        if (requiredBytes <= outputSegment.byteSize()) {
            return outputSegment
        }

        if (requiredBytes > MAX_OUTPUT_CAPACITY_BYTES) {
            val oversizedSegment = allocateAligned(requiredBytes)
            if (preservePrefixBytes > 0L) {
                MemorySegment.copy(outputSegment, 0L, oversizedSegment, 0L, preservePrefixBytes)
            }

            return oversizedSegment
        }

        var nextCapacity = outputSegment.byteSize()
        while (nextCapacity < requiredBytes) {
            nextCapacity = minOf(MAX_OUTPUT_CAPACITY_BYTES, nextCapacity * 2L)
        }

        val grownSegment = allocateAligned(nextCapacity)
        if (preservePrefixBytes > 0L) {
            MemorySegment.copy(outputSegment, 0L, grownSegment, 0L, preservePrefixBytes)
        }

        outputSegment = grownSegment

        return grownSegment
    }

    fun growOutputCapacity(preservePrefixBytes: Long = 0L): Boolean {
        val currentCapacity = outputSegment.byteSize()
        if (currentCapacity >= MAX_OUTPUT_CAPACITY_BYTES) {
            return false
        }

        val nextCapacity = minOf(MAX_OUTPUT_CAPACITY_BYTES, currentCapacity * 2L)
        ensureOutputCapacity(nextCapacity, preservePrefixBytes)

        return true
    }

    fun nativeSource(input: MemorySegment): MemorySegment {
        if (input.isNative && input.address() != 0L) {
            return input
        }

        val byteSize = input.byteSize()
        if (byteSize == 0L) {
            return inputSegment.asSlice(0L, 0L)
        }

        if (byteSize > MAX_OUTPUT_CAPACITY_BYTES) {
            val oversizedInput = allocateAligned(byteSize)
            MemorySegment.copy(input, 0L, oversizedInput, 0L, byteSize)
            return oversizedInput
        }

        ensureInputCapacity(byteSize)
        MemorySegment.copy(input, 0L, inputSegment, 0L, byteSize)

        return inputSegment.asSlice(0L, byteSize)
    }

    fun acquireDescriptorBuffer(descriptorCount: Int): MemorySegment {
        val requiredBytes = maxOf(1, descriptorCount).toLong() * DESCRIPTOR_STRIDE_BYTES
        if (requiredBytes <= sliceDescriptorsSegment.byteSize()) {
            return sliceDescriptorsSegment
        }

        if (descriptorCount > MAX_RETAINED_DESCRIPTORS) {
            return allocateAligned(requiredBytes)
        }

        val grownDescriptors = allocateAligned(requiredBytes)
        sliceDescriptorsSegment = grownDescriptors

        return grownDescriptors
    }

    fun prepareSingleDescriptor(nativeSlice: MemorySegment): MemorySegment {
        sliceDescriptorsSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, 0L, nativeSlice.address())
        sliceDescriptorsSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, 8L, nativeSlice.byteSize())

        return sliceDescriptorsSegment
    }

    fun stageHeapInput(requiredBytes: Long): MemorySegment {
        ensureInputCapacity(requiredBytes)

        return inputSegment
    }

    fun copyToStreamInputBuffer(
        source: MemorySegment,
        sourceOffset: Long,
        byteCount: Int,
    ): ByteBuffer {
        require(byteCount in 0..STREAM_WINDOW_BYTES) {
            "Stream input chunk ($byteCount bytes) must be within 0..$STREAM_WINDOW_BYTES"
        }

        if (byteCount > 0) {
            MemorySegment.copy(source, sourceOffset, streamInputSegment, 0L, byteCount.toLong())
        }

        streamInputByteBuffer.clear()
        streamInputByteBuffer.limit(byteCount)

        return streamInputByteBuffer
    }

    fun prepareStreamOutputWindow(maxBytes: Int = STREAM_WINDOW_BYTES): ByteBuffer {
        val clampedLimit = minOf(maxBytes, STREAM_WINDOW_BYTES)
        streamWindowByteBuffer.clear()
        streamWindowByteBuffer.limit(clampedLimit)

        return streamWindowByteBuffer
    }

    fun readableStreamOutputWindow(producedBytes: Int): ByteBuffer {
        streamWindowByteBuffer.clear()
        streamWindowByteBuffer.limit(producedBytes)

        return streamWindowByteBuffer
    }

    private fun ensureInputCapacity(requiredBytes: Long) {
        if (requiredBytes <= inputSegment.byteSize()) {
            return
        }

        var nextCapacity = inputSegment.byteSize()
        while (nextCapacity < requiredBytes) {
            nextCapacity = minOf(MAX_OUTPUT_CAPACITY_BYTES, nextCapacity * 2L)
        }

        inputSegment = allocateAligned(nextCapacity)
    }

    companion object {

        const val ALIGNMENT_BYTES: Long = 64L
        const val INITIAL_OUTPUT_CAPACITY_BYTES: Long = 1L * 1024L * 1024L
        const val MAX_OUTPUT_CAPACITY_BYTES: Long = 16L * 1024L * 1024L
        const val STREAM_WINDOW_BYTES: Int = 256 * 1024
        const val MAX_SCANNED_GZIP_MEMBERS: Int = 1024
        const val DESCRIPTOR_STRIDE_BYTES: Long = 16L

        private const val INITIAL_INPUT_CAPACITY_BYTES: Long = 64L * 1024L
        private const val INITIAL_DESCRIPTOR_CAPACITY: Int = 32
        private const val MAX_RETAINED_DESCRIPTORS: Int = 4096

        private val threadLocalScratch: ThreadLocal<CompressionScratch> = ThreadLocal.withInitial {
            CompressionScratch()
        }

        fun get(): CompressionScratch = threadLocalScratch.get()

        fun allocateAligned(byteSize: Long): MemorySegment =
            Arena.ofAuto().allocate(maxOf(1L, byteSize), ALIGNMENT_BYTES)

    }

}
