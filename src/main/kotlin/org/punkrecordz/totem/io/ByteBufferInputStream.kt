package org.punkrecordz.totem.io

import java.io.InputStream
import java.nio.ByteBuffer

internal class ByteBufferInputStream(
    private val byteBuffer: ByteBuffer,
) : InputStream() {

    override fun read(): Int {
        if (!byteBuffer.hasRemaining()) {
            return -1
        }

        return byteBuffer.get().toInt() and 0xFF
    }

    override fun read(
        byteArray: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (!byteBuffer.hasRemaining()) {
            return -1
        }

        val bytesToRead = minOf(length, byteBuffer.remaining())

        byteBuffer.get(byteArray, offset, bytesToRead)

        return bytesToRead
    }

}
