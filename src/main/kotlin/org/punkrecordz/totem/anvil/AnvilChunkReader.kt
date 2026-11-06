package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.io.FileChannelIo
import org.punkrecordz.totem.io.compression.CompressionScratch
import org.punkrecordz.totem.io.compression.CompressionType
import java.io.IOException
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel

internal object AnvilChunkReader {

    fun readValidatedPayload(
        state: AnvilRegionState,
        offsetEntry: Int,
    ): CompressedChunkPayload? {
        if (offsetEntry == 0) {
            return null
        }

        val sectorIndex = offsetEntry ushr 8
        val sectorCount = offsetEntry and 0xFF
        val sectorOffset = sectorIndex.toLong() * AnvilSectorTable.SECTOR_SIZE.toLong()
        if (sectorOffset < AnvilSectorTable.HEADER_SIZE.toLong() || sectorCount <= 0) {
            return null
        }

        return when (state) {
            is AnvilRegionState.ReadOnlyLoaded -> {
                val regionBuffer = state.pooledBuffer ?: return null
                extractFromSegment(
                    segment = regionBuffer.segment,
                    baseFileOffset = 0L,
                    actualFileSize = state.actualFileSize,
                    sectorOffset = sectorOffset,
                    sectorCount = sectorCount,
                )
            }
            is AnvilRegionState.SequentialBufferedWrite -> {
                if (sectorOffset >= state.bufferFileBaseOffset) {
                    extractFromSegment(
                        segment = state.pooledBuffer.segment,
                        baseFileOffset = state.bufferFileBaseOffset,
                        actualFileSize = state.logicalFileSize,
                        sectorOffset = sectorOffset,
                        sectorCount = sectorCount,
                    )
                } else {
                    extractFromChannel(
                        fileChannel = state.fileChannel,
                        actualFileSize = state.logicalFileSize,
                        sectorOffset = sectorOffset,
                        sectorCount = sectorCount,
                    )
                }
            }
            is AnvilRegionState.InPlaceWrite -> extractFromChannel(
                fileChannel = state.fileChannel,
                actualFileSize = state.actualFileSize,
                sectorOffset = sectorOffset,
                sectorCount = sectorCount,
            )
            AnvilRegionState.Closed -> throw IllegalStateException("AnvilRegionFile is closed.")
        }
    }

    fun decompressTransient(chunkPayload: CompressedChunkPayload): MemorySegment {
        val payloadSlice = chunkPayload.payload

        return when (chunkPayload.compressionCode) {
            CompressedChunkPayload.COMPRESSION_GZIP -> CompressionType.GZIP.decompressTransient(payloadSlice)
            CompressedChunkPayload.COMPRESSION_ZLIB -> CompressionType.ZLIB.decompressTransient(payloadSlice)
            else -> CompressionType.NONE.decompressTransient(payloadSlice)
        }
    }

    private fun extractFromSegment(
        segment: MemorySegment,
        baseFileOffset: Long,
        actualFileSize: Long,
        sectorOffset: Long,
        sectorCount: Int,
    ): CompressedChunkPayload? {
        val availableBytes = actualFileSize - sectorOffset
        if (availableBytes < AnvilChunkEncodeScratch.CHUNK_HEADER_BYTES.toLong()) {
            return null
        }

        val localOffset = sectorOffset - baseFileOffset
        val maxSectorBytes = sectorCount.toLong() * AnvilSectorTable.SECTOR_SIZE.toLong()

        return parseChunkFrame(
            segment = segment,
            headerOffset = localOffset,
            maxSectorBytes = maxSectorBytes,
            availableBytes = availableBytes,
        )
    }

    private fun extractFromChannel(
        fileChannel: FileChannel,
        actualFileSize: Long,
        sectorOffset: Long,
        sectorCount: Int,
    ): CompressedChunkPayload? {
        if (sectorOffset + AnvilChunkEncodeScratch.CHUNK_HEADER_BYTES > actualFileSize) {
            return null
        }

        val maxSectorBytes = sectorCount.toLong() * AnvilSectorTable.SECTOR_SIZE.toLong()
        val bytesToRead = minOf(maxSectorBytes, actualFileSize - sectorOffset)
        if (bytesToRead <= AnvilChunkEncodeScratch.CHUNK_HEADER_BYTES.toLong()) {
            return null
        }

        val scratch = CompressionScratch.get()
        val destinationSegment = scratch.stageHeapInput(bytesToRead)
        val readSlice = destinationSegment.asSlice(0L, bytesToRead)
        val totalReadBytes = FileChannelIo.readUpTo(fileChannel, sectorOffset, readSlice.asByteBuffer())

        return parseChunkFrame(
            segment = destinationSegment,
            headerOffset = 0L,
            maxSectorBytes = maxSectorBytes,
            availableBytes = totalReadBytes,
        )
    }

    private fun parseChunkFrame(
        segment: MemorySegment,
        headerOffset: Long,
        maxSectorBytes: Long,
        availableBytes: Long,
    ): CompressedChunkPayload? {
        if (availableBytes < AnvilChunkEncodeScratch.CHUNK_HEADER_BYTES.toLong()) {
            return null
        }

        val payloadLength = segment.get(AnvilSectorTable.BIG_ENDIAN_INT, headerOffset)
        val compressionCode = segment.get(ValueLayout.JAVA_BYTE, headerOffset + 4L).toInt() and 0xFF
        val compressedSize = payloadLength.toLong() - 1L

        if (compressedSize <= 0L || 4L + payloadLength.toLong() > maxSectorBytes) {
            return null
        }
        if (AnvilChunkEncodeScratch.CHUNK_HEADER_BYTES.toLong() + compressedSize > availableBytes) {
            return null
        }

        if (compressionCode !in CompressedChunkPayload.COMPRESSION_GZIP..CompressedChunkPayload.COMPRESSION_NONE) {
            throw IOException("Unknown Anvil chunk compression type: $compressionCode")
        }

        return CompressedChunkPayload(
            compressionCode = compressionCode,
            payload = segment.asSlice(headerOffset + AnvilChunkEncodeScratch.CHUNK_HEADER_BYTES.toLong(), compressedSize),
        )
    }

}
