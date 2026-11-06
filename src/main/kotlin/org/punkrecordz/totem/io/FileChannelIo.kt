package org.punkrecordz.totem.io

import java.io.EOFException
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal object FileChannelIo {

    private const val LARGE_IO_WINDOW_BYTES: Long = 64L * 1024L * 1024L

    fun readUpTo(
        fileChannel: FileChannel,
        fileOffset: Long,
        destinationBuffer: ByteBuffer,
    ): Long {
        var currentFileOffset = fileOffset
        val initialPosition = destinationBuffer.position()

        while (destinationBuffer.hasRemaining()) {
            val bytesRead = fileChannel.read(destinationBuffer, currentFileOffset)
            if (bytesRead < 0) {
                break
            }
            currentFileOffset += bytesRead.toLong()
        }

        return (destinationBuffer.position() - initialPosition).toLong()
    }

    fun readSegmentUpTo(
        fileChannel: FileChannel,
        fileOffset: Long,
        destinationSegment: MemorySegment,
        byteCount: Long,
    ): Long {
        if (byteCount <= 0L) {
            return 0L
        }

        var totalRead = 0L
        while (totalRead < byteCount) {
            val windowLength = minOf(byteCount - totalRead, LARGE_IO_WINDOW_BYTES)
            val windowBuffer = destinationSegment.asSlice(totalRead, windowLength).asByteBuffer()
            val windowRead = readUpTo(fileChannel, fileOffset + totalRead, windowBuffer)
            totalRead += windowRead
            if (windowRead < windowLength) {
                break
            }
        }

        return totalRead
    }

    fun writeFullyAt(
        fileChannel: FileChannel,
        fileOffset: Long,
        sourceBuffer: ByteBuffer,
        description: String,
    ) {
        var currentFileOffset = fileOffset
        while (sourceBuffer.hasRemaining()) {
            val bytesWritten = fileChannel.write(sourceBuffer, currentFileOffset)
            if (bytesWritten <= 0) {
                throw EOFException("Failed to write $description at offset $currentFileOffset")
            }
            currentFileOffset += bytesWritten.toLong()
        }
    }

    fun writeSegmentFullyAt(
        fileChannel: FileChannel,
        fileOffset: Long,
        sourceSegment: MemorySegment,
        bufferOffset: Long,
        byteCount: Long,
        description: String,
    ) {
        if (byteCount <= 0L) {
            return
        }

        var remaining = byteCount
        var currentBufferOffset = bufferOffset
        var currentFileOffset = fileOffset

        while (remaining > 0L) {
            val windowLength = minOf(remaining, LARGE_IO_WINDOW_BYTES)
            val windowBuffer = sourceSegment.asSlice(currentBufferOffset, windowLength).asByteBuffer()
            writeFullyAt(fileChannel, currentFileOffset, windowBuffer, description)
            currentBufferOffset += windowLength
            currentFileOffset += windowLength
            remaining -= windowLength
        }
    }

}
