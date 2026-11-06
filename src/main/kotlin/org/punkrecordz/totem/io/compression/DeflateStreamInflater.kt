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

    private const val STREAM_INPUT_CHUNK_BYTES: Long = 16L * 1024L * 1024L
    private const val STREAM_OUTPUT_WINDOW_BYTES: Long = 64L * 1024L * 1024L

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

                    val chunkLength = minOf(remainingCompressed, STREAM_INPUT_CHUNK_BYTES)
                    val inputSlice = input.asSlice(compressedCursor, chunkLength)
                    inflater.setInput(scratch.prepareDirectInputBuffer(inputSlice))
                    compressedCursor += chunkLength
                }

                if (totalProduced == activeSegment.byteSize()) {
                    val nextCapacity = Math.multiplyExact(activeSegment.byteSize(), 2L)
                    activeSegment = when {
                        nextCapacity <= CompressionScratch.MAX_OUTPUT_CAPACITY_BYTES ->
                            scratch.ensureOutputCapacity(nextCapacity, preservePrefixBytes = totalProduced)
                        else -> {
                            val grownSegment = CompressionScratch.allocateAligned(nextCapacity)
                            MemorySegment.copy(activeSegment, 0L, grownSegment, 0L, totalProduced)
                            grownSegment
                        }
                    }
                }

                val availableSpace = minOf(activeSegment.byteSize() - totalProduced, STREAM_OUTPUT_WINDOW_BYTES).toInt()
                val destinationBuffer = when {
                    activeSegment === scratch.outputSegment -> scratch.prepareOutputBuffer(
                        limitBytes = (totalProduced + availableSpace).toInt(),
                        positionBytes = totalProduced.toInt(),
                    )
                    else -> activeSegment.asSlice(totalProduced, availableSpace.toLong()).asByteBuffer()
                }

                val previousProduced = totalProduced
                val producedNow = inflater.inflate(destinationBuffer)
                if (producedNow > 0) {
                    totalProduced += producedNow.toLong()
                    crc32?.update(activeSegment.asSlice(previousProduced, producedNow.toLong()).asByteBuffer())
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

}
