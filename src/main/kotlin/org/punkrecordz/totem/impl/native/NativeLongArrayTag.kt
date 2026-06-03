package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapLongArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.LongArrayTag
import java.lang.Long.reverseBytes
import java.lang.foreign.MemorySegment
import java.nio.ByteOrder
import java.util.function.IntConsumer

@JvmInline
value class NativeLongArrayTag(
    val segment: MemorySegment,
) : LongArrayTag {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    val sizeInBytes: Long
        get() {
            val intSize = MemoryLayouts.INT.byteSize()

            return intSize + (size.toLong() * 8L)
        }

    override fun get(index: Int): Long {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        val intSize = MemoryLayouts.INT.byteSize()

        return segment.get(
            MemoryLayouts.LONG,
            intSize + (index.toLong() * 8L)
        )
    }

    override fun set(index: Int, value: Long) {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        val intSize = MemoryLayouts.INT.byteSize()

        segment.set(
            MemoryLayouts.LONG,
            intSize + (index.toLong() * 8L),
            value
        )
    }

    override fun replace(target: Long, replacement: Long) {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size (65536 longs * 8 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 65536
        val heapBuffer = LongArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        val targetRaw = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            reverseBytes(target)
        } else {
            target
        }

        val replacementRaw = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            reverseBytes(replacement)
        } else {
            replacement
        }

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Long.SIZE_BYTES)

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Long.SIZE_BYTES, endOffset - offset)
            val toCopyLongs = (toCopyBytes / Long.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            var modified = false
            for (index in 0 until toCopyLongs) {
                if (heapBuffer[index] == targetRaw) {
                    heapBuffer[index] = replacementRaw
                    modified = true
                }
            }

            if (modified) {
                MemorySegment.copy(heapSegment, 0L, segment, offset, toCopyBytes)
            }
            offset += toCopyBytes
        }
    }

    override fun replaceAll(replacements: Map<Long, Long>) {
        if (replacements.isEmpty()) {
            return
        }

        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        val isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

        val capacity = 1 shl (32 - (replacements.size * 2).countLeadingZeroBits())
        val mask = capacity - 1
        val keys = LongArray(capacity)
        val values = LongArray(capacity)
        val hasValue = BooleanArray(capacity)

        for ((target, replacement) in replacements) {
            val targetRaw = if (isLittleEndian) {
                reverseBytes(target)
            } else {
                target
            }

            val replacementRaw = if (isLittleEndian) {
                reverseBytes(replacement)
            } else {
                replacement
            }

            var slot = targetRaw.hashCode() and mask
            while (hasValue[slot]) {
                if (keys[slot] == targetRaw) {
                    break
                }
                slot = (slot + 1) and mask
            }
            keys[slot] = targetRaw
            values[slot] = replacementRaw
            hasValue[slot] = true
        }

        // 512kb buffer size (65536 longs * 8 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 65536
        val heapBuffer = LongArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Long.SIZE_BYTES)

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Long.SIZE_BYTES, endOffset - offset)
            val toCopyLongs = (toCopyBytes / Long.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            var modified = false
            for (index in 0 until toCopyLongs) {
                val rawValue = heapBuffer[index]
                var slot = rawValue.hashCode() and mask
                while (hasValue[slot]) {
                    if (keys[slot] == rawValue) {
                        val replacedValue = values[slot]
                        if (heapBuffer[index] != replacedValue) {
                            heapBuffer[index] = replacedValue
                            modified = true
                        }
                        break
                    }
                    slot = (slot + 1) and mask
                }
            }

            if (modified) {
                MemorySegment.copy(heapSegment, 0L, segment, offset, toCopyBytes)
            }
            offset += toCopyBytes
        }
    }


    override fun pin(): LongArrayTag {
        val intSize = MemoryLayouts.INT.byteSize()
        val heapArray = segment.asSlice(intSize, size.toLong() * 8L)
            .toArray(MemoryLayouts.LONG)

        return HeapLongArrayTag(heapArray)
    }

    override fun occurrences(): Map<Long, Int> {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()
        val result = HashMap<Long, Int>()

        // 512kb buffer size (65536 longs * 8 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 65536
        val heapBuffer = LongArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Long.SIZE_BYTES)
        val isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Long.SIZE_BYTES, endOffset - offset)
            val toCopyLongs = (toCopyBytes / Long.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyLongs) {
                val rawValue = heapBuffer[index]
                val element = if (isLittleEndian) reverseBytes(rawValue) else rawValue
                result.merge(element, 1) { old, new -> old + new }
            }
            offset += toCopyBytes
        }

        return result
    }

    override fun forEachIndex(target: Long, action: IntConsumer) {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size (65536 longs * 8 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 65536
        val heapBuffer = LongArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        val isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        val targetRaw = if (isLittleEndian) reverseBytes(target) else target

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Long.SIZE_BYTES)
        var baseIndex = 0

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Long.SIZE_BYTES, endOffset - offset)
            val toCopyLongs = (toCopyBytes / Long.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyLongs) {
                if (heapBuffer[index] == targetRaw) {
                    action.accept(baseIndex + index)
                }
            }

            baseIndex += toCopyLongs
            offset += toCopyBytes
        }
    }

    override fun copy(): LongArrayTag = pin()

}
