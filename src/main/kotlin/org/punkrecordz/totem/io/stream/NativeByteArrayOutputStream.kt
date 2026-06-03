package org.punkrecordz.totem.io.stream

import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class NativeByteArrayOutputStream(
    val arena: Arena,
    private val channel: FileChannel? = null,
) {

    // 4mb chunk size balances virtual memory allocation overhead and page alignment limits
    private val chunkSize = 4 * 1024 * 1024L
    private val chunks = mutableListOf<MemorySegment>()

    // 128mb initial memory map accommodates medium/large files immediately without expensive remapping
    private val initialMapSize = 128 * 1024 * 1024L
    private val singleByte = ByteBuffer.allocate(1)
    private var currentSegment: MemorySegment
    private var position = 0L

    // tracking remap count to issue diagnostics in case of excessive virtual memory churn
    private var remapCount = 0

    var totalWritten = 0L
        private set

    init {
        if (channel != null) {
            singleByte.clear()
            channel.write(singleByte, initialMapSize - 1)

            currentSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0L, initialMapSize, arena)
        } else {
            currentSegment = arena.allocateUninitialized(chunkSize)
        }
    }

    fun writeByte(value: Byte) {
        ensureCapacity(1L)

        currentSegment.set(ValueLayout.JAVA_BYTE, position, value)

        position++
        totalWritten++
    }

    fun writeShort(value: Short) {
        ensureCapacity(2L)

        currentSegment.set(MemoryLayouts.SHORT, position, value)

        position += 2L
        totalWritten += 2L
    }

    fun writeInt(value: Int) {
        ensureCapacity(4L)

        currentSegment.set(MemoryLayouts.INT, position, value)

        position += 4L
        totalWritten += 4L
    }

    fun writeLong(value: Long) {
        ensureCapacity(8L)

        currentSegment.set(MemoryLayouts.LONG, position, value)

        position += 8L
        totalWritten += 8L
    }

    fun writeFloat(value: Float) {
        ensureCapacity(4L)

        currentSegment.set(MemoryLayouts.FLOAT, position, value)

        position += 4L
        totalWritten += 4L
    }

    fun writeDouble(value: Double) {
        ensureCapacity(8L)

        currentSegment.set(MemoryLayouts.DOUBLE, position, value)

        position += 8L
        totalWritten += 8L
    }

    fun writeByteArray(bytes: ByteArray) {
        writeSegment(MemorySegment.ofArray(bytes))
    }

    fun writeIntArray(elements: IntArray) {
        val size = elements.size
        val byteSize = size.toLong() * 4L

        ensureCapacity(byteSize)

        for (index in 0 until size) {
            currentSegment.set(MemoryLayouts.INT, position + index * 4L, elements[index])
        }

        position += byteSize
        totalWritten += byteSize
    }

    fun writeLongArray(elements: LongArray) {
        val size = elements.size
        val byteSize = size.toLong() * 8L

        ensureCapacity(byteSize)

        for (index in 0 until size) {
            currentSegment.set(MemoryLayouts.LONG, position + index * 8L, elements[index])
        }

        position += byteSize
        totalWritten += byteSize
    }

    fun writeString(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)

        writeShort(bytes.size.toShort())
        writeByteArray(bytes)
    }

    fun writeSegment(
        segment: MemorySegment,
        length: Long = segment.byteSize(),
    ) {
        require(length >= 0L) {
            "Segment write length ($length) must be non-negative"
        }

        ensureCapacity(length)

        MemorySegment.copy(segment, 0L, currentSegment, position, length)

        position += length
        totalWritten += length
    }

    private fun ensureCapacity(needed: Long) {
        require(needed >= 0L) {
            "Requested capacity size check ($needed) must be non-negative"
        }

        if (position + needed > currentSegment.byteSize()) {
            if (channel != null) {
                val currentSize = currentSegment.byteSize()
                val newSize = maxOf(currentSize * 2L, position + needed)

                remapCount++
                // warn if remapping > 3 times as it signals bad initialMapSize selection and risks address space fragmentation
                if (remapCount > 3) {
                    System.err.println(
                        StringBuilder().apply {
                            append("WARNING: NativeByteArrayOutputStream has remapped the memory-mapped file $remapCount times. ")
                            append("This can lead to significant virtual memory fragmentation and performance overhead. ")
                            append("Consider increasing 'initialMapSize' (currently ${initialMapSize / 1024 / 1024}MB) to accommodate large files.")
                        }.toString()
                    )
                }

                singleByte.clear()
                channel.write(singleByte, newSize - 1)

                currentSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0L, newSize, arena)
            } else {
                chunks.add(currentSegment.asSlice(0L, position))

                val nextCapacity = maxOf(chunkSize, needed)
                currentSegment = arena.allocateUninitialized(nextCapacity)
                position = 0L
            }
        }
    }

    fun toContiguousSegment(targetArena: Arena): MemorySegment {
        val result = targetArena.allocateUninitialized(totalWritten)
        var offset = 0L

        for (chunk in chunks) {
            MemorySegment.copy(chunk, 0L, result, offset, chunk.byteSize())
            offset += chunk.byteSize()
        }

        if (position > 0) {
            MemorySegment.copy(currentSegment, 0L, result, offset, position)
        }

        return result
    }

}
