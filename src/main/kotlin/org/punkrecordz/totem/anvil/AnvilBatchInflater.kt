package org.punkrecordz.totem.anvil

import java.io.IOException
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Future

internal object AnvilBatchInflater {

    private const val ENTRY_ABSENT: Long = -1L
    private const val ENTRY_CORRUPT: Long = -2L
    private const val ENTRY_UNKNOWN_COMPRESSION: Long = -4L

    // sequential windows stay cache-resident; parallel windows are large enough to balance the native pool
    private const val PARALLEL_WINDOW_CHUNKS: Int = 256
    private const val SEQUENTIAL_WINDOW_CHUNKS: Int = 8

    fun forEach(
        state: AnvilRegionState.ReadOnlyLoaded,
        sectorTable: AnvilSectorTable,
        parallel: Boolean,
        consumer: ChunkSliceConsumer,
    ) {
        val regionBuffer = state.pooledBuffer ?: return
        val regionSegment = regionBuffer.segment.asSlice(0L, state.actualFileSize)
        val windowChunks = if (parallel) PARALLEL_WINDOW_CHUNKS else SEQUENTIAL_WINDOW_CHUNKS
        val windowStarts = (0 until AnvilSectorTable.CHUNK_COUNT step windowChunks).filter { firstChunk ->
            sectorTable.hasAnyChunk(firstChunk, windowChunks)
        }

        when (parallel) {
            true -> forEachPipelined(state, sectorTable, regionSegment, windowStarts, windowChunks, consumer)
            false -> AnvilInflateWindow(windowChunks).use { window ->
                for (firstChunk in windowStarts) {
                    window.inflate(regionSegment, firstChunk, parallel = false)
                    dispatchWindow(state, sectorTable, window, consumer)
                }
            }
        }
    }

    // the native pool inflates window N + 1 while the caller consumes window N
    private fun forEachPipelined(
        state: AnvilRegionState.ReadOnlyLoaded,
        sectorTable: AnvilSectorTable,
        regionSegment: MemorySegment,
        windowStarts: List<Int>,
        windowChunks: Int,
        consumer: ChunkSliceConsumer,
    ) {
        if (windowStarts.isEmpty()) {
            return
        }

        val windows = arrayOf(AnvilInflateWindow(windowChunks), AnvilInflateWindow(windowChunks))
        var inFlight: Future<*>? = null

        try {
            windows[0].inflate(regionSegment, windowStarts[0], parallel = true)

            for (windowNumber in windowStarts.indices) {
                val currentWindow = windows[windowNumber and 1]
                val nextWindow = windows[(windowNumber + 1) and 1]

                inFlight?.let { pendingTask ->
                    AnvilNativeExecutor.awaitCompletion(pendingTask, rethrowFailure = true)
                    inFlight = null
                }

                inFlight = windowStarts.getOrNull(windowNumber + 1)?.let { nextFirstChunk ->
                    AnvilNativeExecutor.executor.submit {
                        nextWindow.inflate(regionSegment, nextFirstChunk, parallel = true)
                    }
                }

                dispatchWindow(state, sectorTable, currentWindow, consumer)
            }
        } finally {
            inFlight?.let { pendingTask -> AnvilNativeExecutor.awaitCompletion(pendingTask, rethrowFailure = false) }
            windows.forEach { window -> window.close() }
        }
    }

    private fun dispatchWindow(
        state: AnvilRegionState.ReadOnlyLoaded,
        sectorTable: AnvilSectorTable,
        window: AnvilInflateWindow,
        consumer: ChunkSliceConsumer,
    ) {
        val output = window.output.segment
        val entries = window.entries

        for (slot in 0 until window.chunkCount) {
            val chunkIndex = window.firstChunk + slot
            val entryOffset = entries.getAtIndex(ValueLayout.JAVA_LONG, slot * 2L)
            val entryLength = entries.getAtIndex(ValueLayout.JAVA_LONG, slot * 2L + 1L)

            val uncompressedNbt = when {
                entryLength >= 0L -> output.asSlice(entryOffset, entryLength)
                entryLength == ENTRY_ABSENT -> continue
                entryLength == ENTRY_UNKNOWN_COMPRESSION -> throw IOException(
                    "Unknown Anvil chunk compression type: $entryOffset",
                )
                // corrupt or oversized payloads replay the scalar path for its streaming fallback and precise errors
                entryLength == ENTRY_CORRUPT -> {
                    val validatedPayload =
                        AnvilChunkReader.readValidatedPayload(state, sectorTable.offsets[chunkIndex]) ?: continue
                    AnvilChunkReader.decompressTransient(validatedPayload)
                }
                else -> throw IllegalStateException(
                    "Unexpected native batch entry status $entryLength for chunk index $chunkIndex",
                )
            }

            consumer.accept(chunkIndex and 31, chunkIndex ushr 5, uncompressedNbt)
        }
    }

}
