package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.ffi.TotemCodecSys
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference

internal class AnvilInflateWindow(
    val chunkCount: Int,
) : AutoCloseable {

    // auto scope: filled by a native-executor thread, read by the caller, and reclaimed on GC
    val entries: MemorySegment =
        Arena.ofAuto().allocate(2L * ValueLayout.JAVA_LONG.byteSize() * chunkCount, ValueLayout.JAVA_LONG.byteSize())

    var output: PooledRegionBuffer = AnvilRegionBufferPool.acquire(AnvilRegionBufferPool.DEFAULT_BUFFER_BYTES)
        private set

    var firstChunk: Int = 0
        private set

    fun inflate(
        regionSegment: MemorySegment,
        windowFirstChunk: Int,
        parallel: Boolean,
    ) {
        firstChunk = windowFirstChunk

        var usedBytes = invokeInflate(regionSegment, windowFirstChunk, output, parallel)
        if (usedBytes < 0L && usedBytes != TotemCodecSys.STATUS_INVALID_ARGUMENT) {
            val previousOutput = output
            output = AnvilRegionBufferPool.acquire(-usedBytes)
            AnvilRegionBufferPool.release(previousOutput)
            usedBytes = invokeInflate(regionSegment, windowFirstChunk, output, parallel)
        }

        check(usedBytes >= 0L) {
            "Native batch inflate of chunks $windowFirstChunk..${windowFirstChunk + chunkCount - 1} failed with status $usedBytes"
        }
    }

    override fun close() {
        AnvilRegionBufferPool.release(output)
    }

    private fun invokeInflate(
        regionSegment: MemorySegment,
        windowFirstChunk: Int,
        destinationBuffer: PooledRegionBuffer,
        parallel: Boolean,
    ): Long = try {
        TotemCodecSys.inflateChunks(
            regionSegment,
            windowFirstChunk,
            chunkCount,
            destinationBuffer.segment,
            entries,
            parallel,
        )
    } finally {
        Reference.reachabilityFence(regionSegment)
        Reference.reachabilityFence(destinationBuffer)
        Reference.reachabilityFence(entries)
    }

}
