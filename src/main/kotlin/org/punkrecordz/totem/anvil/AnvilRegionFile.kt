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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.concurrent.locks.StampedLock

class AnvilRegionFile(
    file: File,
    val writeMode: Boolean = false,
) : AutoCloseable {

    private companion object {
        const val SECTOR_SIZE = 4096
        const val HEADER_SECTORS = 2
        const val HEADER_SIZE = SECTOR_SIZE * HEADER_SECTORS
        const val CHUNK_COUNT = 1024
        const val COMPRESSION_GZIP = 1
        const val COMPRESSION_DEFLATE = 2
    }

    private val channel: FileChannel
    private val arena: Arena = Arena.ofShared()
    private val mappedSegment: MemorySegment?
    private val offsets = IntArray(CHUNK_COUNT)
    private val timestamps = IntArray(CHUNK_COUNT)
    private val usedSectors = BitSet()
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
                
                tempSegment = null

                if (!exists || size < HEADER_SIZE) {
                    Arena.ofConfined().use { tempArena ->
                        val headerSegment = tempArena.allocateUninitialized(HEADER_SIZE.toLong())
                        headerSegment.fill(0.toByte())
                        tempChannel.write(headerSegment.asByteBuffer(), 0L)
                    }
                    usedSectors.set(0, HEADER_SECTORS)
                } else {
                    loadHeadersFromChannel(tempChannel)
                    recalculateUsedSectors()
                }
            } else {
                tempChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
                val size = tempChannel.size()
                
                tempSegment = if (size >= HEADER_SIZE) {
                    tempChannel.map(FileChannel.MapMode.READ_ONLY, 0L, size, arena)
                } else {
                    null
                }
                
                loadHeadersFromMapping(tempSegment)
                recalculateUsedSectors()
            }

            channel = tempChannel
            mappedSegment = tempSegment
        } catch (exception: Throwable) {
            try {
                tempChannel?.close()
            } catch (closeException: Throwable) {
                exception.addSuppressed(closeException)
            }
            try {
                arena.close()
            } catch (closeException: Throwable) {
                exception.addSuppressed(closeException)
            }
            throw exception
        }
    }

    fun listChunks(): List<ChunkCoordinate> {
        if (!writeMode) {
            if (closed) {
                throw IllegalStateException("AnvilRegionFile is closed.")
            }
            
            return readChunksFromOffsets()
        }

        val activeLock = lock!!
        val stamp = activeLock.tryOptimisticRead()
        val isClosed = closed
        val chunkList = readChunksFromOffsets()

        if (!activeLock.validate(stamp)) {
            val readStamp = activeLock.readLock()
            try {
                if (closed) {
                    throw IllegalStateException("AnvilRegionFile is closed.")
                }
                
                return readChunksFromOffsets()
            } finally {
                activeLock.unlockRead(readStamp)
            }
        }

        if (isClosed) {
            throw IllegalStateException("AnvilRegionFile is closed.")
        }

        return chunkList
    }

    private fun readChunksFromOffsets(): List<ChunkCoordinate> {
        val chunks = ArrayList<ChunkCoordinate>()
        offsets.forEachIndexed { index, entry ->
            if (entry != 0) {
                chunks.add(
                    ChunkCoordinate(
                        x = index % 32,
                        z = index / 32,
                    ),
                )
            }
        }
        
        return chunks
    }

    fun readChunk(
        globalX: Int,
        globalZ: Int,
        targetArena: Arena,
    ): Pair<String, CompoundTag>? {
        if (!writeMode) {
            if (closed) {
                throw IllegalStateException("AnvilRegionFile is closed.")
            }
            
            return performReadChunk(globalX, globalZ, targetArena)
        }

        val activeLock = lock!!
        val stamp = activeLock.readLock()
        try {
            if (closed) {
                throw IllegalStateException("AnvilRegionFile is closed.")
            }
            
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
        val offsetEntry = offsets[getChunkIndex(globalX, globalZ)]
        if (offsetEntry == 0) return null

        val sectorOffset = offsetEntry ushr 8
        val sectorCount = offsetEntry and 0xFF

        if (sectorOffset < HEADER_SECTORS || sectorCount == 0) return null
        val fileOffset = sectorOffset.toLong() * SECTOR_SIZE

        return if (mappedSegment != null) {
            val chunkSegment = mappedSegment.asSlice(fileOffset, sectorCount.toLong() * SECTOR_SIZE)
            val length = chunkSegment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0L)

            if (length > sectorCount * SECTOR_SIZE) return null

            val compressionType = chunkSegment.get(ValueLayout.JAVA_BYTE, 4L).toInt()
            val compressedSegment = chunkSegment.asSlice(5L, length - 1L)

            val compression = when (compressionType) {
                COMPRESSION_GZIP -> CompressionType.GZIP
                COMPRESSION_DEFLATE -> CompressionType.ZLIB
                else -> throw IOException("Unknown Anvil chunk compression type: $compressionType")
            }

            Totem.load(compressedSegment, targetArena, compression)
        } else {
            val headerSegment = targetArena.allocateUninitialized(5L)
            channel.read(headerSegment.asByteBuffer(), fileOffset)

            val length = headerSegment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0L)
            if (length > sectorCount * SECTOR_SIZE) return null

            val compressionType = headerSegment.get(ValueLayout.JAVA_BYTE, 4L).toInt()
            val compressedSegment = targetArena.allocateUninitialized(length - 1L)
            channel.read(compressedSegment.asByteBuffer(), fileOffset + 5L)

            val compression = when (compressionType) {
                COMPRESSION_GZIP -> CompressionType.GZIP
                COMPRESSION_DEFLATE -> CompressionType.ZLIB
                else -> throw IOException("Unknown Anvil chunk compression type: $compressionType")
            }

            Totem.load(compressedSegment, targetArena, compression)
        }
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

        val chunkIndex = getChunkIndex(globalX, globalZ)

        Arena.ofConfined().use { tempArena ->
            val compressedSegment = Totem.save(rootName, tag, tempArena, CompressionType.ZLIB)
            val totalDataSize = compressedSegment.byteSize().toInt() + 5
            val requiredSectors = (totalDataSize + SECTOR_SIZE - 1) / SECTOR_SIZE

            if (requiredSectors > 255) {
                throw IllegalArgumentException("Chunk size $totalDataSize bytes exceeds maximum sectors size limit of 255 sectors.")
            }

            val dataLength = totalDataSize - 4
            val paddingSize = (requiredSectors * SECTOR_SIZE) - totalDataSize
            val totalWriteSize = totalDataSize + paddingSize

            val writeSegment = tempArena.allocateUninitialized(totalWriteSize.toLong())

            writeSegment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0L, dataLength)
            writeSegment.set(ValueLayout.JAVA_BYTE, 4L, COMPRESSION_DEFLATE.toByte())

            MemorySegment.copy(compressedSegment, 0L, writeSegment, 5L, compressedSegment.byteSize())

            if (paddingSize > 0) {
                writeSegment.asSlice(5L + compressedSegment.byteSize(), paddingSize.toLong()).fill(0.toByte())
            }

            val activeLock = lock!!
            val stamp = activeLock.writeLock()
            try {
                if (closed) {
                    throw IllegalStateException("AnvilRegionFile is closed.")
                }

                val oldEntry = offsets[chunkIndex]

                if (oldEntry != 0) {
                    val oldOffset = oldEntry ushr 8
                    val oldCount = oldEntry and 0xFF
                    usedSectors.clear(oldOffset, oldOffset + oldCount)
                }

                val newSectorOffset = findFreeSectors(requiredSectors)
                val fileOffset = newSectorOffset.toLong() * SECTOR_SIZE

                channel.write(writeSegment.asByteBuffer(), fileOffset)

                val newEntry = (newSectorOffset shl 8) or requiredSectors
                val newTimestamp = (System.currentTimeMillis() / 1000).toInt()

                offsets[chunkIndex] = newEntry
                timestamps[chunkIndex] = newTimestamp

                usedSectors.set(newSectorOffset, newSectorOffset + requiredSectors)

                val headerSegment = tempArena.allocateUninitialized(8)
                headerSegment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0L, newEntry)
                headerSegment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 4L, newTimestamp)

                channel.write(headerSegment.asSlice(0, 4).asByteBuffer(), chunkIndex.toLong() * 4L)
                channel.write(headerSegment.asSlice(4, 4).asByteBuffer(), SECTOR_SIZE.toLong() + chunkIndex.toLong() * 4L)
            } finally {
                activeLock.unlockWrite(stamp)
            }
        }
    }

    private fun findFreeSectors(required: Int): Int {
        var startCheck = HEADER_SECTORS
        while (true) {
            val nextClear = usedSectors.nextClearBit(startCheck)
            val nextSet = usedSectors.nextSetBit(nextClear)

            if (nextSet == -1) return nextClear
            if (nextSet - nextClear >= required) return nextClear

            startCheck = nextSet
        }
    }

    private fun loadHeadersFromMapping(segment: MemorySegment?) {
        if (segment == null) return

        for (index in 0 until CHUNK_COUNT) {
            offsets[index] = segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), (index * 4).toLong())
            timestamps[index] = segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), (SECTOR_SIZE + index * 4).toLong())
        }
    }

    private fun loadHeadersFromChannel(activeChannel: FileChannel) {
        val size = activeChannel.size()

        if (size < HEADER_SIZE) return

        Arena.ofConfined().use { tempArena ->
            val headerSegment = tempArena.allocateUninitialized(HEADER_SIZE.toLong())
            activeChannel.read(headerSegment.asByteBuffer(), 0L)

            for (index in 0 until CHUNK_COUNT) {
                offsets[index] = headerSegment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), index * 4L)
                timestamps[index] = headerSegment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), (SECTOR_SIZE + index * 4L))
            }
        }
    }

    private fun recalculateUsedSectors() {
        usedSectors.clear()
        usedSectors.set(0, HEADER_SECTORS)
        offsets.forEach { entry ->
            if (entry != 0) {
                val start = entry ushr 8
                val count = entry and 0xFF
                usedSectors.set(start, start + count)
            }
        }
    }

    private fun getChunkIndex(chunkX: Int, chunkZ: Int): Int {
        return ((chunkX % 32 + 32) % 32) + ((chunkZ % 32 + 32) % 32) * 32
    }

    override fun close() {
        if (writeMode) {
            val activeLock = lock!!
            val stamp = activeLock.writeLock()
            try {
                if (closed) return
                closed = true

                Arena.ofConfined().use { tempArena ->
                    val headerSegment = tempArena.allocateUninitialized(HEADER_SIZE.toLong())
                    
                    for (index in 0 until CHUNK_COUNT) {
                        headerSegment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), index * 4L, offsets[index])
                        headerSegment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), SECTOR_SIZE + index * 4L, timestamps[index])
                    }
                    
                    channel.write(headerSegment.asByteBuffer(), 0L)
                }

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
