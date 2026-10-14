package org.punkrecordz.totem.io.inspect

import org.punkrecordz.totem.io.memory.OffHeapScratchBuffer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal class NbtInspectReader(
    private val sourceSegment: MemorySegment,
    private val sourceChannel: FileChannel?,
    private val sourceTotalSize: Long,
    private val isGzip: Boolean,
    val outputSegment: MemorySegment,
    initialBytesRead: Long,
    val maxUncompressedBytes: Long,
) : AutoCloseable {

    var currentLimit: Long = initialBytesRead
    var uncompressedDelta: Long = 0L
    var openCompounds: Int = 0
    var topLevelScalarTags: Int = 0

    private var prefixFullyExpanded: Boolean = initialBytesRead >= maxUncompressedBytes
    private var gzipMemberIndex: GzipMemberIndex? = null
    private var gzipIndexInitialized: Boolean = false
    private var fullSourceSegment: MemorySegment? =
        if (sourceSegment.byteSize() == sourceTotalSize) sourceSegment else null
    private var lazyMapArena: Arena? = null

    fun ensurePrefixAvailable(requiredOffset: Long): Boolean {
        if (requiredOffset <= currentLimit) return true
        if (topLevelScalarTags >= 3 || uncompressedDelta > 0L || prefixFullyExpanded || currentLimit >= maxUncompressedBytes) {
            return false
        }
        prefixFullyExpanded = true

        if (!isGzip) {
            val remainingCap = minOf(sourceTotalSize, maxUncompressedBytes) - currentLimit
            if (remainingCap > 0L) {
                if (sourceChannel != null) {
                    currentLimit += GzipPrefixInspector.readChannelSlice(
                        sourceChannel,
                        currentLimit,
                        outputSegment.asSlice(currentLimit, remainingCap),
                    )
                } else if (sourceSegment.byteSize() > currentLimit) {
                    val copyLength = minOf(sourceSegment.byteSize() - currentLimit, remainingCap)
                    MemorySegment.copy(sourceSegment, currentLimit, outputSegment, currentLimit, copyLength)
                    currentLimit += copyLength
                }
            }
            return requiredOffset <= currentLimit
        }

        val expandedInput = if (sourceChannel != null && sourceSegment.byteSize() < sourceTotalSize) {
            val readCap = minOf(sourceTotalSize, maxOf(128 * 1024L, maxUncompressedBytes))
            val expandedSegment = OffHeapScratchBuffer.getCompressionBuffer().acquire(readCap)
            val readBytes = GzipPrefixInspector.readChannelSlice(sourceChannel, 0L, expandedSegment)
            expandedSegment.asSlice(0L, readBytes)
        } else {
            sourceSegment
        }

        currentLimit = GzipPrefixInspector.inflateGzipPrefixOffHeap(
            inputSegment = expandedInput,
            startCompressedOffset = 0L,
            outputSegment = outputSegment,
            outputOffset = 0L,
            maxBytesToWrite = maxUncompressedBytes,
        )
        return requiredOffset <= currentLimit
    }

    fun readFromUncompressedOffset(
        targetUncompressedOffset: Long,
        destinationOffset: Long,
    ): Long {
        val maxToCopy = maxUncompressedBytes - destinationOffset
        if (maxToCopy <= 0L) return 0L

        if (!isGzip) {
            if (targetUncompressedOffset >= sourceTotalSize) return 0L
            val available = minOf(sourceTotalSize - targetUncompressedOffset, maxToCopy)
            val fullSegment = fullSourceSegment
            if (fullSegment != null) {
                MemorySegment.copy(fullSegment, targetUncompressedOffset, outputSegment, destinationOffset, available)
                return available
            }
            if (sourceChannel != null) {
                return GzipPrefixInspector.readChannelSlice(
                    sourceChannel,
                    targetUncompressedOffset,
                    outputSegment.asSlice(destinationOffset, available),
                )
            }
            return 0L
        }

        if (!gzipIndexInitialized) {
            gzipIndexInitialized = true
            if (fullSourceSegment == null && sourceChannel != null && sourceTotalSize >= 18L) {
                val trailerSlice = outputSegment.asSlice(destinationOffset, minOf(4L, maxToCopy))
                if (trailerSlice.byteSize() == 4L) {
                    GzipPrefixInspector.readChannelSlice(sourceChannel, sourceTotalSize - 4L, trailerSlice)
                    val lastMemberIsize = trailerSlice.get(
                        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                        0L,
                    ).toLong() and 0xFFFFFFFFL
                    if (lastMemberIsize > 16 * 1024 * 1024L) return 0L
                }
                val mapArena = Arena.ofConfined()
                lazyMapArena = mapArena
                fullSourceSegment = sourceChannel.map(FileChannel.MapMode.READ_ONLY, 0L, sourceTotalSize, mapArena)
            }
            fullSourceSegment?.let { segmentToScan ->
                gzipMemberIndex = GzipPrefixInspector.buildGzipMemberIndex(segmentToScan)
            }
        }

        val index = gzipMemberIndex ?: return 0L
        val segmentToInflate = fullSourceSegment ?: return 0L
        if (targetUncompressedOffset >= index.totalUncompressedSize) return 0L

        var memberIndex = index.memberUncompressedOffsets.binarySearch(targetUncompressedOffset)
        if (memberIndex < 0) memberIndex = -memberIndex - 2
        if (memberIndex < 0) return 0L

        return GzipPrefixInspector.inflateGzipPrefixOffHeap(
            inputSegment = segmentToInflate,
            startCompressedOffset = index.memberCompressedOffsets[memberIndex],
            outputSegment = outputSegment,
            outputOffset = destinationOffset,
            maxBytesToWrite = maxToCopy,
            initialBytesToSkip = targetUncompressedOffset - index.memberUncompressedOffsets[memberIndex],
        )
    }

    override fun close() {
        lazyMapArena?.close()
    }

}
