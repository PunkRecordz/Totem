package org.punkrecordz.totem.anvil

import java.io.File
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal sealed interface AnvilRegionState {

    class ReadOnlyLoaded(
        val pooledBuffer: PooledRegionBuffer?,
        val actualFileSize: Long,
    ) : AnvilRegionState

    class SequentialBufferedWrite(
        val fileChannel: FileChannel,
        val pooledBuffer: PooledRegionBuffer,
        var bufferFileBaseOffset: Long = 0L,
        var bufferWritePosition: Long = AnvilSectorTable.HEADER_SIZE.toLong(),
        var nextSectorIndex: Int = AnvilSectorTable.HEADER_SECTORS,
        var headerDirty: Boolean = true,
    ) : AnvilRegionState {

        val logicalFileSize: Long
            get() = bufferFileBaseOffset + bufferWritePosition

        fun appendFramedSectors(
            sectorTable: AnvilSectorTable,
            chunkIndex: Int,
            framedSlice: MemorySegment,
            requiredSectors: Int,
        ) {
            val byteSize = framedSlice.byteSize()
            val allocatedSectorOffset = nextSectorIndex
            nextSectorIndex += requiredSectors
            sectorTable.setChunkEntrySequential(chunkIndex, allocatedSectorOffset, requiredSectors)
            headerDirty = true

            if (bufferWritePosition + byteSize > pooledBuffer.capacityBytes) {
                flushCurrentBlock(sectorTable)
            }

            MemorySegment.copy(framedSlice, 0L, pooledBuffer.segment, bufferWritePosition, byteSize)
            bufferWritePosition += byteSize
        }

        fun flushPending(sectorTable: AnvilSectorTable) {
            if (bufferFileBaseOffset == 0L) {
                if (bufferWritePosition > 0L || headerDirty) {
                    sectorTable.writeHeadersToSegment(pooledBuffer.segment)
                    pooledBuffer.writeToChannel(fileChannel, 0L, bufferWritePosition, 0L)
                    bufferFileBaseOffset = bufferWritePosition
                    bufferWritePosition = 0L
                    headerDirty = false
                }
                return
            }

            if (bufferWritePosition > 0L) {
                pooledBuffer.writeToChannel(fileChannel, 0L, bufferWritePosition, bufferFileBaseOffset)
                bufferFileBaseOffset += bufferWritePosition
                bufferWritePosition = 0L
            }

            if (headerDirty) {
                sectorTable.writeHeadersToSegment(pooledBuffer.segment)
                pooledBuffer.writeToChannel(fileChannel, 0L, AnvilSectorTable.HEADER_SIZE.toLong(), 0L)
                headerDirty = false
            }
        }

        private fun flushCurrentBlock(sectorTable: AnvilSectorTable) {
            if (bufferWritePosition <= 0L) {
                return
            }

            if (bufferFileBaseOffset == 0L) {
                sectorTable.writeHeadersToSegment(pooledBuffer.segment)
            }

            pooledBuffer.writeToChannel(fileChannel, 0L, bufferWritePosition, bufferFileBaseOffset)
            bufferFileBaseOffset += bufferWritePosition
            bufferWritePosition = 0L
        }

    }

    class InPlaceWrite(
        val fileChannel: FileChannel,
        var actualFileSize: Long,
        var headerDirty: Boolean = false,
    ) : AnvilRegionState {

        fun flushPending(sectorTable: AnvilSectorTable) {
            if (!headerDirty) {
                return
            }

            AnvilChunkEncodeScratch.get().writeHeaderToChannel(fileChannel, sectorTable)
            actualFileSize = maxOf(actualFileSize, AnvilSectorTable.HEADER_SIZE.toLong())
            headerDirty = false
        }

    }

    data object Closed : AnvilRegionState

    fun flushPendingWrites(sectorTable: AnvilSectorTable) = when (this) {
        is SequentialBufferedWrite -> flushPending(sectorTable)
        is InPlaceWrite -> flushPending(sectorTable)
        is ReadOnlyLoaded -> Unit
        Closed -> throw IllegalStateException("AnvilRegionFile is closed.")
    }

    fun closeRegion(sectorTable: AnvilSectorTable) {
        when (this) {
            is ReadOnlyLoaded -> pooledBuffer?.let { buffer -> AnvilRegionBufferPool.release(buffer) }
            is SequentialBufferedWrite -> try {
                flushPending(sectorTable)
            } finally {
                AnvilRegionBufferPool.release(pooledBuffer)
                fileChannel.close()
            }
            is InPlaceWrite -> try {
                flushPending(sectorTable)
            } finally {
                fileChannel.close()
            }
            Closed -> Unit
        }
    }

    companion object {

        fun open(
            file: File,
            writeMode: Boolean,
            truncateExisting: Boolean,
            sectorTable: AnvilSectorTable,
        ): AnvilRegionState {
            if (writeMode) {
                file.parentFile?.mkdirs()
            }

            var openedChannel: FileChannel? = null
            var acquiredBuffer: PooledRegionBuffer? = null

            try {
                if (!writeMode) {
                    return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { readChannel ->
                        val actualFileSize = readChannel.size()
                        if (actualFileSize >= AnvilSectorTable.HEADER_SIZE.toLong()) {
                            val buffer = AnvilRegionBufferPool.acquire(actualFileSize)
                            acquiredBuffer = buffer
                            val bytesRead = buffer.readFromChannel(readChannel, actualFileSize)
                            sectorTable.loadFromSegment(buffer.segment.asSlice(0L, bytesRead))
                            ReadOnlyLoaded(pooledBuffer = buffer, actualFileSize = bytesRead)
                        } else {
                            ReadOnlyLoaded(pooledBuffer = null, actualFileSize = actualFileSize)
                        }
                    }
                }

                val fileExists = file.exists()
                val existingLength = if (fileExists) file.length() else 0L
                val useSequentialMode = truncateExisting || !fileExists || existingLength < AnvilSectorTable.HEADER_SIZE

                if (useSequentialMode) {
                    openedChannel = FileChannel.open(
                        file.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                    acquiredBuffer = AnvilRegionBufferPool.acquire()
                    return SequentialBufferedWrite(
                        fileChannel = openedChannel,
                        pooledBuffer = acquiredBuffer,
                    )
                }

                openedChannel = FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
                val initialSize = openedChannel.size()
                AnvilChunkEncodeScratch.get().readHeaderFromChannel(openedChannel, sectorTable)

                return InPlaceWrite(
                    fileChannel = openedChannel,
                    actualFileSize = initialSize,
                )
            } catch (exception: Throwable) {
                runCatching { openedChannel?.close() }.onFailure { suppressed -> exception.addSuppressed(suppressed) }
                acquiredBuffer?.let { buffer -> AnvilRegionBufferPool.release(buffer) }
                throw exception
            }
        }

    }

}
