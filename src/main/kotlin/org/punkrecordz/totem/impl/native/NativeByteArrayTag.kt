package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapByteArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.MemorySegment
import java.util.function.IntConsumer

@JvmInline
value class NativeByteArrayTag(
    val segment: MemorySegment,
) : ByteArrayTag {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    val sizeInBytes: Long
        get() = segment.byteSize()

    override fun get(index: Int): Byte {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        val intSize = MemoryLayouts.INT.byteSize()

        return segment.get(MemoryLayouts.BYTE, intSize + index.toLong())
    }

    override fun set(index: Int, value: Byte) {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        val intSize = MemoryLayouts.INT.byteSize()

        segment.set(MemoryLayouts.BYTE, intSize + index.toLong(), value)
    }

    override fun replace(target: Byte, replacement: Byte) {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 524288
        val heapBuffer = ByteArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + size.toLong()

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong(), endOffset - offset)

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            var modified = false
            for (index in 0 until toCopyBytes.toInt()) {
                if (heapBuffer[index] == target) {
                    heapBuffer[index] = replacement
                    modified = true
                }
            }

            if (modified) {
                MemorySegment.copy(heapSegment, 0L, segment, offset, toCopyBytes)
            }
            offset += toCopyBytes
        }
    }

    override fun replaceAll(replacements: Map<Byte, Byte>) {
        if (replacements.isEmpty()) {
            return
        }

        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        val lookupTable = ByteArray(256) { index ->
            index.toByte()
        }

        for ((target, replacement) in replacements) {
            lookupTable[target.toInt() and 0xFF] = replacement
        }

        // 512kb buffer size to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 524288
        val heapBuffer = ByteArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + size.toLong()

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong(), endOffset - offset)

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            var modified = false
            for (index in 0 until toCopyBytes.toInt()) {
                val unsignedValue = heapBuffer[index].toInt() and 0xFF
                val replacedValue = lookupTable[unsignedValue]
                if (heapBuffer[index] != replacedValue) {
                    heapBuffer[index] = replacedValue
                    modified = true
                }
            }

            if (modified) {
                MemorySegment.copy(heapSegment, 0L, segment, offset, toCopyBytes)
            }
            offset += toCopyBytes
        }
    }


    override fun pin(): ByteArrayTag {
        val intSize = MemoryLayouts.INT.byteSize()
        val heapArray = segment.asSlice(intSize, size.toLong()).toArray(MemoryLayouts.BYTE)

        return HeapByteArrayTag(heapArray)
    }

    override fun occurrences(): Map<Byte, Int> {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()
        val frequencyArray = IntArray(256)

        // 512kb buffer size to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 524288
        val heapBuffer = ByteArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + size.toLong()

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong(), endOffset - offset)

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyBytes.toInt()) {
                val unsignedValue = heapBuffer[index].toInt() and 0xFF
                frequencyArray[unsignedValue]++
            }
            offset += toCopyBytes
        }

        val result = HashMap<Byte, Int>()

        for (unsignedValue in 0 until 256) {
            val count = frequencyArray[unsignedValue]
            if (count > 0) {
                result[unsignedValue.toByte()] = count
            }
        }

        return result
    }

    override fun forEachIndex(target: Byte, action: IntConsumer) {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 524288
        val heapBuffer = ByteArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + size.toLong()
        var baseIndex = 0

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong(), endOffset - offset)

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyBytes.toInt()) {
                if (heapBuffer[index] == target) {
                    action.accept(baseIndex + index)
                }
            }

            baseIndex += toCopyBytes.toInt()
            offset += toCopyBytes
        }
    }

    override fun copy(): ByteArrayTag = pin()

}
