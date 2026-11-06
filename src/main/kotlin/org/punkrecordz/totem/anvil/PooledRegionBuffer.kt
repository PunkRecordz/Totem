package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.io.FileChannelIo
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class PooledRegionBuffer(
    val segment: MemorySegment,
    val isRecyclable: Boolean,
    private val ownedArena: Arena? = null,
) {

    private val cachedByteBuffer: ByteBuffer? =
        if (segment.byteSize() <= Int.MAX_VALUE.toLong()) segment.asByteBuffer() else null

    val capacityBytes: Long
        get() = segment.byteSize()

    fun closeOwnedArena() {
        ownedArena?.close()
    }

    fun readFromChannel(
        fileChannel: FileChannel,
        byteCount: Long,
    ): Long {
        if (byteCount <= 0L) {
            return 0L
        }

        val directBuffer = cachedByteBuffer
        if (directBuffer != null && byteCount <= Int.MAX_VALUE.toLong()) {
            directBuffer.clear()
            directBuffer.limit(byteCount.toInt())

            return FileChannelIo.readUpTo(fileChannel, 0L, directBuffer)
        }

        return FileChannelIo.readSegmentUpTo(fileChannel, 0L, segment, byteCount)
    }

    fun writeToChannel(
        fileChannel: FileChannel,
        bufferOffset: Long,
        byteCount: Long,
        fileOffset: Long,
    ) {
        if (byteCount <= 0L) {
            return
        }

        val directBuffer = cachedByteBuffer
        if (directBuffer != null && bufferOffset + byteCount <= Int.MAX_VALUE.toLong()) {
            directBuffer.clear()
            directBuffer.position(bufferOffset.toInt())
            directBuffer.limit((bufferOffset + byteCount).toInt())
            FileChannelIo.writeFullyAt(fileChannel, fileOffset, directBuffer, "region buffer")
            return
        }

        FileChannelIo.writeSegmentFullyAt(
            fileChannel = fileChannel,
            fileOffset = fileOffset,
            sourceSegment = segment,
            bufferOffset = bufferOffset,
            byteCount = byteCount,
            description = "region slice",
        )
    }

    companion object {

        fun owned(
            arena: Arena,
            byteSize: Long,
        ): PooledRegionBuffer = PooledRegionBuffer(
            segment = arena.allocateUninitialized(byteSize),
            isRecyclable = false,
            ownedArena = arena,
        )

    }

}
