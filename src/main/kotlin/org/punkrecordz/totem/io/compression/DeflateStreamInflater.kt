package org.punkrecordz.totem.io.compression

import java.lang.foreign.MemorySegment
import java.lang.ref.Reference
import java.util.zip.CRC32
import java.util.zip.Inflater

internal class InflatedStreamState(
    val segment: MemorySegment,
    val totalProducedBytes: Long,
)

internal object DeflateStreamInflater {

    fun inflateStream(
        input: MemorySegment,
        startOffset: Long,
        endOffset: Long,
        inflater: Inflater,
        scratch: CompressionScratch,
        initialSegment: MemorySegment,
        initialProducedBytes: Long,
        crc32: CRC32?,
        formatName: String,
    ): InflatedStreamState {
        var compressedCursor = startOffset
        var totalProduced = initialProducedBytes
        var activeSegment = initialSegment

        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    val remainingCompressed = endOffset - compressedCursor
                    if (remainingCompressed <= 0L) {
                        throw IllegalArgumentException(
                            "Truncated $formatName stream: reached end of compressed input after $totalProduced output bytes",
                        )
                    }

                    val chunkLength = minOf(remainingCompressed, CompressionScratch.STREAM_WINDOW_BYTES.toLong()).toInt()
                    val inputWindow = scratch.copyToStreamInputBuffer(input, compressedCursor, chunkLength)
                    inflater.setInput(inputWindow)
                    compressedCursor += chunkLength.toLong()
                }

                // always inflate into an auto-arena scratch window to avoid shared-session ByteBuffer crashes on jdk 21-24
                val outputWindow = scratch.prepareStreamOutputWindow()
                val producedNow = inflater.inflate(outputWindow)
                if (producedNow > 0) {
                    crc32?.update(scratch.readableStreamOutputWindow(producedNow))

                    val requiredCapacity = Math.addExact(totalProduced, producedNow.toLong())
                    if (requiredCapacity > activeSegment.byteSize()) {
                        activeSegment = growDestinationSegment(activeSegment, totalProduced, requiredCapacity, scratch)
                    }

                    MemorySegment.copy(
                        scratch.streamWindowSegment,
                        0L,
                        activeSegment,
                        totalProduced,
                        producedNow.toLong(),
                    )
                    totalProduced = requiredCapacity
                } else {
                    if (inflater.needsDictionary()) {
                        throw IllegalArgumentException("$formatName stream requires an unsupported preset dictionary")
                    }
                    if (!inflater.finished() && !inflater.needsInput()) {
                        throw IllegalArgumentException("$formatName stream stalled without finishing or requesting input")
                    }
                }
            }

            return InflatedStreamState(activeSegment, totalProduced)
        } finally {
            Reference.reachabilityFence(input)
        }
    }

    private fun growDestinationSegment(
        currentSegment: MemorySegment,
        preservedBytes: Long,
        requiredCapacity: Long,
        scratch: CompressionScratch,
    ): MemorySegment {
        var nextCapacity = maxOf(1L, currentSegment.byteSize())
        while (nextCapacity < requiredCapacity) {
            nextCapacity = Math.multiplyExact(nextCapacity, 2L)
        }

        if (nextCapacity <= CompressionScratch.MAX_OUTPUT_CAPACITY_BYTES) {
            return scratch.ensureOutputCapacity(nextCapacity, preservePrefixBytes = preservedBytes)
        }

        val grownSegment = CompressionScratch.allocateAligned(nextCapacity)
        if (preservedBytes > 0L) {
            MemorySegment.copy(currentSegment, 0L, grownSegment, 0L, preservedBytes)
        }

        return grownSegment
    }

}
