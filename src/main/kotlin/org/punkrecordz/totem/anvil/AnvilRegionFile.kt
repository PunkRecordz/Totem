package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.Totem
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.CompoundTag
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.StampedLock

class AnvilRegionFile(
    file: File,
    val writeMode: Boolean = false,
) : AutoCloseable {

    private val channel: FileChannel
    private val arena: Arena = Arena.ofShared()
    private val mappedSegment: MemorySegment?
    private val sectorTable = AnvilSectorTable()
    private val lock: StampedLock? = if (writeMode) StampedLock() else null

    @Volatile
    private var closed = false

    init {
        file.parentFile?.mkdirs()

        var tempChannel: FileChannel? = null
        var tempSegment: MemorySegment? = null
        try {
            if (writeMode) {
                val exists = file.exists()
                val size = if (exists) file.length() else 0L

                tempChannel = FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )

                if (!exists || size < AnvilSectorTable.HEADER_SIZE) {
                    sectorTable.initializeEmptyHeader(tempChannel)
                } else {
                    sectorTable.loadFromChannel(tempChannel)
                }
            } else {
                tempChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
                val size = tempChannel.size()

                tempSegment = if (size >= AnvilSectorTable.HEADER_SIZE) {
                    tempChannel.map(FileChannel.MapMode.READ_ONLY, 0L, size, arena)
                } else {
                    null
                }

                sectorTable.loadFromMapping(tempSegment)
            }

            channel = tempChannel
            mappedSegment = tempSegment
        } catch (exception: Throwable) {
            runCatching { tempChannel?.close() }.onFailure { exception.addSuppressed(it) }
            runCatching { arena.close() }.onFailure { exception.addSuppressed(it) }
            throw exception
        }
    }

    fun listChunks(): List<ChunkCoordinate> {
        if (!writeMode) {
            check(!closed) { "AnvilRegionFile is closed." }
            return sectorTable.listPresentChunks()
        }

        val activeLock = lock!!
        val stamp = activeLock.tryOptimisticRead()
        val isClosed = closed
        val chunkList = sectorTable.listPresentChunks()

        if (!activeLock.validate(stamp)) {
            val readStamp = activeLock.readLock()
            try {
                check(!closed) { "AnvilRegionFile is closed." }
                return sectorTable.listPresentChunks()
            } finally {
                activeLock.unlockRead(readStamp)
            }
        }

        check(!isClosed) { "AnvilRegionFile is closed." }
        return chunkList
    }

    fun readChunk(
        globalX: Int,
        globalZ: Int,
        targetArena: Arena,
    ): Pair<String, CompoundTag>? {
        if (!writeMode) {
            check(!closed) { "AnvilRegionFile is closed." }
            return performReadChunk(globalX, globalZ, targetArena)
        }

        val activeLock = lock!!
        val stamp = activeLock.readLock()
        try {
            check(!closed) { "AnvilRegionFile is closed." }
            return performReadChunk(globalX, globalZ, targetArena)
        } finally {
            activeLock.unlockRead(stamp)
        }
    }

    private fun performReadChunk(
        globalX: Int,
        globalZ: Int,
        targetArena: Arena,
    ): Pair<String, CompoundTag>? {
        val offsetEntry = sectorTable.offsets[AnvilSectorTable.getChunkIndex(globalX, globalZ)]
        if (offsetEntry == 0) return null

        val sectorOffset = offsetEntry ushr 8
        val sectorCount = offsetEntry and 0xFF

        if (sectorOffset < AnvilSectorTable.HEADER_SECTORS || sectorCount == 0) return null
        val fileOffset = sectorOffset.toLong() * AnvilSectorTable.SECTOR_SIZE
        val sectorBytes = sectorCount.toLong() * AnvilSectorTable.SECTOR_SIZE

        val chunkSegment = if (mappedSegment != null) {
            mappedSegment.asSlice(fileOffset, sectorBytes)
        } else {
            val tempBuffer = targetArena.allocateUninitialized(sectorBytes)
            channel.read(tempBuffer.asByteBuffer(), fileOffset)
            tempBuffer
        }

        val length = chunkSegment.get(AnvilSectorTable.BIG_ENDIAN_INT, 0L)
        if (length > sectorBytes || length <= 1) return null

        val compressionType = chunkSegment.get(ValueLayout.JAVA_BYTE, 4L).toInt()
        val compression = when (compressionType) {
            AnvilSectorTable.COMPRESSION_GZIP -> CompressionType.GZIP
            AnvilSectorTable.COMPRESSION_DEFLATE -> CompressionType.ZLIB
            else -> throw IOException("Unknown Anvil chunk compression type: $compressionType")
        }

        val compressedSegment = chunkSegment.asSlice(5L, length - 1L)
        return Totem.load(compressedSegment, targetArena, compression)
    }

    fun writeChunk(
        globalX: Int,
        globalZ: Int,
        rootName: String,
        tag: CompoundTag,
    ) {
        require(writeMode) {
            "Cannot write chunk on a read-only AnvilRegionFile mapping."
        }

        val chunkIndex = AnvilSectorTable.getChunkIndex(globalX, globalZ)

        Arena.ofConfined().use { tempArena ->
            val compressedSegment = Totem.save(rootName, tag, tempArena, CompressionType.ZLIB)
            val totalDataSize = compressedSegment.byteSize().toInt() + 5
            val requiredSectors = (totalDataSize + AnvilSectorTable.SECTOR_SIZE - 1) / AnvilSectorTable.SECTOR_SIZE

            require(requiredSectors <= 255) {
                "Chunk size $totalDataSize bytes exceeds maximum sectors size limit of 255 sectors."
            }

            val dataLength = totalDataSize - 4
            val paddingSize = (requiredSectors * AnvilSectorTable.SECTOR_SIZE) - totalDataSize
            val totalWriteSize = totalDataSize + paddingSize

            val writeSegment = tempArena.allocateUninitialized(totalWriteSize.toLong())
            writeSegment.set(AnvilSectorTable.BIG_ENDIAN_INT, 0L, dataLength)
            writeSegment.set(ValueLayout.JAVA_BYTE, 4L, AnvilSectorTable.COMPRESSION_DEFLATE.toByte())
            MemorySegment.copy(compressedSegment, 0L, writeSegment, 5L, compressedSegment.byteSize())

            if (paddingSize > 0) {
                writeSegment.asSlice(5L + compressedSegment.byteSize(), paddingSize.toLong()).fill(0.toByte())
            }

            val activeLock = lock!!
            val stamp = activeLock.writeLock()
            try {
                check(!closed) { "AnvilRegionFile is closed." }

                val newSectorOffset = sectorTable.allocateChunkSectors(chunkIndex, requiredSectors)
                val fileOffset = newSectorOffset.toLong() * AnvilSectorTable.SECTOR_SIZE

                channel.write(writeSegment.asByteBuffer(), fileOffset)
                sectorTable.writeChunkHeaderEntry(channel, tempArena, chunkIndex)
            } finally {
                activeLock.unlockWrite(stamp)
            }
        }
    }

    override fun close() {
        if (writeMode) {
            val activeLock = lock!!
            val stamp = activeLock.writeLock()
            try {
                if (closed) return
                closed = true

                sectorTable.flushAllHeaders(channel)
                arena.close()
                channel.close()
            } finally {
                activeLock.unlockWrite(stamp)
            }
        } else {
            synchronized(this) {
                if (closed) return
                closed = true

                arena.close()
                channel.close()
            }
        }
    }

}
