package org.punkrecordz.totem.anvil

import java.io.File
import java.lang.foreign.MemorySegment
import java.util.concurrent.locks.ReentrantReadWriteLock

internal class AnvilRegionAccess(
    file: File,
    private val writeMode: Boolean,
    truncateExisting: Boolean,
    parallelCompression: Boolean,
    compressionLevel: Int,
    val sectorTable: AnvilSectorTable,
) {

    // reentrant read/write lock guards both writable mutations and read-only pooled-buffer lifecycle on close()
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    private val staging: AnvilChunkStaging? =
        if (writeMode && parallelCompression) AnvilChunkStaging(compressionLevel) else null

    @Volatile
    private var state: AnvilRegionState = AnvilRegionState.open(file, writeMode, truncateExisting, sectorTable)

    val isStaging: Boolean
        get() = staging != null

    fun ensureOpen() {
        check(state !is AnvilRegionState.Closed) { "AnvilRegionFile is closed." }
    }

    fun <T> read(block: (AnvilRegionState) -> T): T {
        if (staging != null && lock.readHoldCount == 0 && !lock.isWriteLockedByCurrentThread) {
            return write { lockedState ->
                drainStaging(lockedState)
                block(lockedState)
            }
        }

        val readLock = lock.readLock()
        readLock.lock()
        try {
            return block(openStateOrThrow())
        } finally {
            readLock.unlock()
        }
    }

    fun <T> write(block: (AnvilRegionState) -> T): T {
        check(writeMode) { "Cannot write chunk on a read-only AnvilRegionFile mapping." }
        val writeLock = lock.writeLock()
        writeLock.lock()
        try {
            return block(openStateOrThrow())
        } finally {
            writeLock.unlock()
        }
    }

    fun commitFramedSectors(
        chunkIndex: Int,
        framedSectors: MemorySegment,
    ) = write { lockedState ->
        if (staging?.contains(chunkIndex) == true) {
            drainStaging(lockedState)
        }

        AnvilChunkCommitter.commit(lockedState, sectorTable, chunkIndex, framedSectors)
    }

    fun stageRawChunk(
        chunkIndex: Int,
        uncompressedNbt: MemorySegment,
    ) = write { lockedState ->
        val activeStaging = checkNotNull(staging) { "Chunk staging requires parallelCompression = true." }
        if (!activeStaging.canAccept(uncompressedNbt.byteSize())) {
            activeStaging.rotate { stagedChunkIndex, framedSectors ->
                AnvilChunkCommitter.commit(lockedState, sectorTable, stagedChunkIndex, framedSectors)
            }
        }

        activeStaging.stage(chunkIndex, uncompressedNbt)
    }

    fun flush() {
        if (!writeMode) {
            read { }
            return
        }

        write { lockedState ->
            drainStaging(lockedState)
            lockedState.flushPendingWrites(sectorTable)
        }
    }

    fun close() {
        val writeLock = lock.writeLock()
        writeLock.lock()
        try {
            val previousState = state
            if (previousState is AnvilRegionState.Closed) {
                return
            }
            state = AnvilRegionState.Closed

            try {
                if (writeMode) {
                    drainStaging(previousState)
                }
            } finally {
                try {
                    staging?.release()
                } finally {
                    previousState.closeRegion(sectorTable)
                }
            }
        } finally {
            writeLock.unlock()
        }
    }

    private fun openStateOrThrow(): AnvilRegionState {
        val observedState = state
        check(observedState !is AnvilRegionState.Closed) { "AnvilRegionFile is closed." }

        return observedState
    }

    private fun drainStaging(lockedState: AnvilRegionState) {
        staging?.drain { chunkIndex, framedSectors ->
            AnvilChunkCommitter.commit(lockedState, sectorTable, chunkIndex, framedSectors)
        }
    }

}
