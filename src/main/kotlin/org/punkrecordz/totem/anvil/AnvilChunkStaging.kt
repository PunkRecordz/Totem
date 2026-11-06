package org.punkrecordz.totem.anvil

import java.lang.foreign.MemorySegment
import java.util.concurrent.Future

// double-buffered staging: one batch is compressed by the native thread pool while the caller fills the other
internal class AnvilChunkStaging(
    compressionLevel: Int,
) {

    private val batches: Array<AnvilStagingBatch> = Array(2) { AnvilStagingBatch(compressionLevel) }
    private var activeIndex: Int = 0
    private var inFlightCompression: Future<*>? = null

    private val activeBatch: AnvilStagingBatch
        get() = batches[activeIndex]

    private val pendingBatch: AnvilStagingBatch
        get() = batches[activeIndex xor 1]

    fun contains(chunkIndex: Int): Boolean = batches.any { batch -> batch.contains(chunkIndex) }

    fun canAccept(rawByteSize: Long): Boolean = activeBatch.canAccept(rawByteSize)

    fun stage(
        chunkIndex: Int,
        rawNbt: MemorySegment,
    ) = activeBatch.stage(chunkIndex, rawNbt)

    fun rotate(commit: (chunkIndex: Int, framedSectors: MemorySegment) -> Unit) {
        completePending(commit)

        val batchToCompress = activeBatch
        inFlightCompression = AnvilNativeExecutor.executor.submit { batchToCompress.compress() }
        activeIndex = activeIndex xor 1
    }

    fun drain(commit: (chunkIndex: Int, framedSectors: MemorySegment) -> Unit) {
        completePending(commit)
        activeBatch.commitTo(commit)
    }

    fun release() {
        val compression = inFlightCompression
        if (compression != null) {
            AnvilNativeExecutor.awaitCompletion(compression, rethrowFailure = false)
            inFlightCompression = null
        }

        batches.forEach { batch -> batch.release() }
    }

    private fun completePending(commit: (chunkIndex: Int, framedSectors: MemorySegment) -> Unit) {
        val compression = inFlightCompression ?: return

        try {
            AnvilNativeExecutor.awaitCompletion(compression, rethrowFailure = true)
        } catch (failure: Throwable) {
            inFlightCompression = null
            pendingBatch.discard()
            throw failure
        }

        inFlightCompression = null
        pendingBatch.commitTo(commit)
    }

}
