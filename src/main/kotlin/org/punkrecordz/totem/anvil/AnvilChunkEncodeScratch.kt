package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.TotemTagIo
import org.punkrecordz.totem.codec.CompoundProtocol
import org.punkrecordz.totem.ffi.TotemCodecSys
import org.punkrecordz.totem.impl.native.NativeRootCompoundTag
import org.punkrecordz.totem.io.FileChannelIo
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.compression.CompressionScratch
import org.punkrecordz.totem.tag.CompoundTag
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class AnvilChunkEncodeScratch {

    private var writeNbtSegment: MemorySegment =
        CompressionScratch.allocateAligned(INITIAL_NBT_CAPACITY_BYTES)

    private var writeNbtByteBuffer: ByteBuffer = writeNbtSegment.asByteBuffer()

    private var sectorPayloadSegment: MemorySegment =
        CompressionScratch.allocateAligned(INITIAL_SECTOR_PAYLOAD_CAPACITY_BYTES)

    private var sectorPayloadByteBuffer: ByteBuffer = sectorPayloadSegment.asByteBuffer()

    fun serializeCompound(
        rootName: String,
        tag: CompoundTag,
    ): MemorySegment {
        if (tag is NativeRootCompoundTag && tag.rootName == rootName) {
            return tag.rootSegment
        }

        val headerSize =
            MemoryLayouts.BYTE.byteSize() + MemoryLayouts.SHORT.byteSize() + MemoryLayouts.stringByteLength(rootName)
        val contentSize = CompoundProtocol.sizeOf(tag)
        val totalNbtBytes = headerSize + contentSize

        require(totalNbtBytes <= Int.MAX_VALUE.toLong()) {
            "Serialized chunk NBT size ($totalNbtBytes bytes) exceeds Int.MAX_VALUE"
        }

        val targetSegment = acquireWriteNbtSegment(totalNbtBytes)
        TotemTagIo.writeUncompressedCompound(targetSegment, rootName, tag, contentSize)

        return targetSegment.asSlice(0L, totalNbtBytes)
    }

    fun encodeCompoundToFramedSectors(
        rootName: String,
        tag: CompoundTag,
        level: Int,
    ): MemorySegment = encodeRawNbtToFramedSectors(serializeCompound(rootName, tag), level)

    fun encodeRawNbtToFramedSectors(
        uncompressedNbt: MemorySegment,
        level: Int,
    ): MemorySegment {
        val uncompressedBytes = uncompressedNbt.byteSize()
        require(uncompressedBytes >= 3L && uncompressedBytes <= Int.MAX_VALUE.toLong()) {
            "Uncompressed chunk NBT size ($uncompressedBytes bytes) must be in 3..${Int.MAX_VALUE}"
        }

        val nativeSource = when {
            uncompressedNbt.isNative && uncompressedNbt.address() != 0L -> uncompressedNbt
            else -> {
                val stagedSource = acquireWriteNbtSegment(uncompressedBytes)
                MemorySegment.copy(uncompressedNbt, 0L, stagedSource, 0L, uncompressedBytes)
                stagedSource.asSlice(0L, uncompressedBytes)
            }
        }

        val frameBound = TotemCodecSys.frameBound(uncompressedBytes)
        val destinationSegment = acquireSectorPayloadSegment(frameBound).asSlice(0L, frameBound)

        val paddedBytes = try {
            TotemCodecSys.frameChunk(level, nativeSource, destinationSegment)
        } finally {
            Reference.reachabilityFence(uncompressedNbt)
            Reference.reachabilityFence(nativeSource)
        }

        return when {
            paddedBytes > 0L -> destinationSegment.asSlice(0L, paddedBytes)
            paddedBytes == TotemCodecSys.STATUS_CHUNK_TOO_LARGE -> throw IllegalArgumentException(
                "Chunk of $uncompressedBytes raw NBT bytes compresses beyond the Anvil limit of $MAX_CHUNK_SECTORS sectors.",
            )
            else -> throw IllegalStateException(
                "Failed to ZLIB-frame chunk payload of $uncompressedBytes bytes (native status $paddedBytes)",
            )
        }
    }

    fun encodeCompressedToFramedSectors(
        compressionCode: Int,
        compressedPayload: MemorySegment,
    ): MemorySegment {
        val payloadBytes = compressedPayload.byteSize()
        val framedBytes = CHUNK_HEADER_BYTES.toLong() + payloadBytes
        val paddedBytes = (framedBytes + AnvilSectorTable.SECTOR_SIZE - 1L) /
            AnvilSectorTable.SECTOR_SIZE * AnvilSectorTable.SECTOR_SIZE

        require(payloadBytes > 0L && paddedBytes / AnvilSectorTable.SECTOR_SIZE <= MAX_CHUNK_SECTORS) {
            "Compressed chunk payload of $payloadBytes bytes must be non-empty and fit in $MAX_CHUNK_SECTORS sectors."
        }

        val destinationSegment = acquireSectorPayloadSegment(paddedBytes)
        destinationSegment.set(AnvilSectorTable.BIG_ENDIAN_INT, 0L, (payloadBytes + 1L).toInt())
        destinationSegment.set(ValueLayout.JAVA_BYTE, 4L, compressionCode.toByte())
        MemorySegment.copy(compressedPayload, 0L, destinationSegment, CHUNK_HEADER_BYTES.toLong(), payloadBytes)
        destinationSegment.asSlice(framedBytes, paddedBytes - framedBytes).fill(0.toByte())

        return destinationSegment.asSlice(0L, paddedBytes)
    }

    fun writeExternalSectorsToChannel(
        fileChannel: FileChannel,
        framedSectors: MemorySegment,
        fileOffset: Long,
    ) {
        val byteSize = framedSectors.byteSize()
        val byteBuffer = when {
            framedSectors.isNative && framedSectors.address() != 0L -> framedSectors.asByteBuffer()
            byteSize <= MAX_RETAINED_SCRATCH_BYTES -> {
                acquireSectorPayloadSegment(byteSize)
                MemorySegment.copy(framedSectors, 0L, sectorPayloadSegment, 0L, byteSize)
                sectorPayloadByteBuffer.clear()
                sectorPayloadByteBuffer.limit(byteSize.toInt())
                sectorPayloadByteBuffer
            }
            else -> {
                val transientSegment = CompressionScratch.allocateAligned(byteSize)
                MemorySegment.copy(framedSectors, 0L, transientSegment, 0L, byteSize)
                transientSegment.asByteBuffer()
            }
        }

        FileChannelIo.writeFullyAt(fileChannel, fileOffset, byteBuffer, "external chunk sectors")
    }

    fun readHeaderFromChannel(
        fileChannel: FileChannel,
        sectorTable: AnvilSectorTable,
    ) {
        writeNbtByteBuffer.clear()
        writeNbtByteBuffer.limit(AnvilSectorTable.HEADER_SIZE)

        val bytesRead = FileChannelIo.readUpTo(fileChannel, 0L, writeNbtByteBuffer)
        if (bytesRead >= AnvilSectorTable.HEADER_SIZE.toLong()) {
            sectorTable.loadFromSegment(writeNbtSegment.asSlice(0L, AnvilSectorTable.HEADER_SIZE.toLong()))
        }
    }

    fun writeHeaderToChannel(
        fileChannel: FileChannel,
        sectorTable: AnvilSectorTable,
    ) {
        sectorTable.writeHeadersToSegment(writeNbtSegment)
        writeNbtByteBuffer.clear()
        writeNbtByteBuffer.limit(AnvilSectorTable.HEADER_SIZE)

        FileChannelIo.writeFullyAt(fileChannel, 0L, writeNbtByteBuffer, "Anvil region header")
    }

    private fun acquireWriteNbtSegment(requiredBytes: Long): MemorySegment {
        if (requiredBytes <= writeNbtSegment.byteSize()) {
            return writeNbtSegment
        }
        if (requiredBytes > MAX_RETAINED_SCRATCH_BYTES) {
            return CompressionScratch.allocateAligned(requiredBytes)
        }

        val grownSegment = CompressionScratch.allocateAligned(
            growRetainedCapacity(writeNbtSegment.byteSize(), requiredBytes),
        )
        writeNbtSegment = grownSegment
        writeNbtByteBuffer = grownSegment.asByteBuffer()

        return grownSegment
    }

    private fun acquireSectorPayloadSegment(requiredBytes: Long): MemorySegment {
        if (requiredBytes <= sectorPayloadSegment.byteSize()) {
            return sectorPayloadSegment
        }
        if (requiredBytes > MAX_RETAINED_SCRATCH_BYTES) {
            return CompressionScratch.allocateAligned(requiredBytes)
        }

        val grownSegment = CompressionScratch.allocateAligned(
            growRetainedCapacity(sectorPayloadSegment.byteSize(), requiredBytes),
        )
        sectorPayloadSegment = grownSegment
        sectorPayloadByteBuffer = grownSegment.asByteBuffer()

        return grownSegment
    }

    private fun growRetainedCapacity(
        currentCapacity: Long,
        requiredBytes: Long,
    ): Long {
        var nextCapacity = currentCapacity
        while (nextCapacity < requiredBytes) {
            nextCapacity = minOf(MAX_RETAINED_SCRATCH_BYTES, nextCapacity * 2L)
        }

        return nextCapacity
    }

    companion object {

        const val CHUNK_HEADER_BYTES: Int = 5
        const val MAX_CHUNK_SECTORS: Int = 255
        const val DEFAULT_ZLIB_LEVEL: Int = 1

        private const val INITIAL_NBT_CAPACITY_BYTES: Long = 1L * 1024L * 1024L
        private const val INITIAL_SECTOR_PAYLOAD_CAPACITY_BYTES: Long = 2L * 1024L * 1024L
        private const val MAX_RETAINED_SCRATCH_BYTES: Long = 16L * 1024L * 1024L

        private val threadLocalEncodeScratch: ThreadLocal<AnvilChunkEncodeScratch> = ThreadLocal.withInitial {
            AnvilChunkEncodeScratch()
        }

        fun get(): AnvilChunkEncodeScratch = threadLocalEncodeScratch.get()

    }

}
