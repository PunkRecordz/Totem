package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.view.ShortView
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.function.IntConsumer

@JvmInline
value class ShortArrayView(
    val segment: MemorySegment,
) : ShortView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    override operator fun get(index: Int): Short {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index is out of bounds for ShortArrayView of size $size.")
        }

        return segment.get(
            NATIVE_SHORT,
            MemoryLayouts.INT.byteSize() + (index.toLong() * Short.SIZE_BYTES),
        )
    }

    override operator fun set(index: Int, value: Short) {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index is out of bounds for ShortArrayView of size $size.")
        }

        segment.set(
            NATIVE_SHORT,
            MemoryLayouts.INT.byteSize() + (index.toLong() * Short.SIZE_BYTES),
            value,
        )
    }


    fun copyInto(
        destination: ShortArray,
        destinationOffset: Int,
        startIndex: Int,
        endIndex: Int,
    ) {
        val length = endIndex - startIndex
        if (length < 0) {
            throw IllegalArgumentException("The provided endIndex ($endIndex) must be greater than or equal to startIndex ($startIndex).")
        }
        if (length == 0) return

        val sourceOffsetInBytes = MemoryLayouts.INT.byteSize() + startIndex.toLong() * Short.SIZE_BYTES
        val destinationOffsetInBytes = destinationOffset.toLong() * Short.SIZE_BYTES
        val lengthInBytes = length.toLong() * Short.SIZE_BYTES

        MemorySegment.copy(
            segment,
            sourceOffsetInBytes,
            MemorySegment.ofArray(destination),
            destinationOffsetInBytes,
            lengthInBytes,
        )
    }

    fun setAll(
        startIndex: Int,
        source: ShortArray,
        sourceOffset: Int,
        length: Int,
    ) {
        if (length < 0) {
            throw IllegalArgumentException("The provided length ($length) must be greater than or equal to 0.")
        }
        if (length == 0) return

        val destinationOffsetInBytes = MemoryLayouts.INT.byteSize() + startIndex.toLong() * Short.SIZE_BYTES
        val sourceOffsetInBytes = sourceOffset.toLong() * Short.SIZE_BYTES
        val lengthInBytes = length.toLong() * Short.SIZE_BYTES

        MemorySegment.copy(
            MemorySegment.ofArray(source),
            sourceOffsetInBytes,
            segment,
            destinationOffsetInBytes,
            lengthInBytes,
        )
    }

    fun setAll(
        startIndex: Int,
        sourceSegment: MemorySegment,
        sourceOffset: Int,
        length: Int,
    ) {
        if (length < 0) {
            throw IllegalArgumentException("The provided length ($length) must be greater than or equal to 0.")
        }
        if (length == 0) return

        val destinationOffsetInBytes = MemoryLayouts.INT.byteSize() + startIndex.toLong() * Short.SIZE_BYTES
        val sourceOffsetInBytes = sourceOffset.toLong() * Short.SIZE_BYTES
        val lengthInBytes = length.toLong() * Short.SIZE_BYTES

        MemorySegment.copy(
            sourceSegment,
            sourceOffsetInBytes,
            segment,
            destinationOffsetInBytes,
            lengthInBytes,
        )
    }

    override fun replace(target: Short, replacement: Short) {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Short.SIZE_BYTES)

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Short.SIZE_BYTES, endOffset - offset)
            val toCopyShorts = (toCopyBytes / Short.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            var modified = false
            for (index in 0 until toCopyShorts) {
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

    override fun replaceAll(replacements: Map<Short, Short>) {
        if (replacements.isEmpty()) return

        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 65536 represents 2^16, the total number of possible unsigned short values
        val lookupTable = ShortArray(65536) { index -> index.toShort() }
        for ((target, replacement) in replacements) {
            lookupTable[target.toInt() and 0xFFFF] = replacement
        }

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Short.SIZE_BYTES)

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Short.SIZE_BYTES, endOffset - offset)
            val toCopyShorts = (toCopyBytes / Short.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            var modified = false
            for (index in 0 until toCopyShorts) {
                val rawValue = heapBuffer[index].toInt() and 0xFFFF
                val replaced = lookupTable[rawValue]
                if (heapBuffer[index] != replaced) {
                    heapBuffer[index] = replaced
                    modified = true
                }
            }

            if (modified) {
                MemorySegment.copy(heapSegment, 0L, segment, offset, toCopyBytes)
            }

            offset += toCopyBytes
        }
    }

    override fun occurrences(): Map<Short, Int> {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()
        // 65536 represents 2^16, the total number of possible unsigned short values
        val frequencyArray = IntArray(65536)

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Short.SIZE_BYTES)

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Short.SIZE_BYTES, endOffset - offset)
            val toCopyShorts = (toCopyBytes / Short.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyShorts) {
                val unsignedValue = heapBuffer[index].toInt() and 0xFFFF
                frequencyArray[unsignedValue]++
            }

            offset += toCopyBytes
        }

        val result = HashMap<Short, Int>()
        // 65536 represents the unsigned short capacity range
        for (unsignedValue in 0 until 65536) {
            val count = frequencyArray[unsignedValue]
            if (count > 0) {
                result[unsignedValue.toShort()] = count
            }
        }

        return result
    }

    override fun indexOf(target: Short): Int {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Short.SIZE_BYTES)
        var baseIndex = 0

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Short.SIZE_BYTES, endOffset - offset)
            val toCopyShorts = (toCopyBytes / Short.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyShorts) {
                if (heapBuffer[index] == target) {
                    return baseIndex + index
                }
            }

            baseIndex += toCopyShorts
            offset += toCopyBytes
        }

        return -1
    }

    override fun firstIndices(): Map<Short, Int> {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()
        val tempIndices = IntArray(65536) { -1 }

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Short.SIZE_BYTES)
        var baseIndex = 0

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Short.SIZE_BYTES, endOffset - offset)
            val toCopyShorts = (toCopyBytes / Short.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyShorts) {
                val unsignedVal = heapBuffer[index].toInt() and 0xFFFF
                if (tempIndices[unsignedVal] == -1) {
                    tempIndices[unsignedVal] = baseIndex + index
                }
            }

            baseIndex += toCopyShorts
            offset += toCopyBytes
        }

        // Construct the clean public Map<Short, Int> from the primitive fast lookup
        val result = HashMap<Short, Int>()
        for (unsignedVal in 0 until 65536) {
            val idx = tempIndices[unsignedVal]
            if (idx != -1) {
                result[unsignedVal.toShort()] = idx
            }
        }

        return result
    }

    override fun forEachIndex(target: Short, action: IntConsumer) {
        val size = this.size
        val intSize = MemoryLayouts.INT.byteSize()

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * Short.SIZE_BYTES)
        var baseIndex = 0

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * Short.SIZE_BYTES, endOffset - offset)
            val toCopyShorts = (toCopyBytes / Short.SIZE_BYTES).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyShorts) {
                if (heapBuffer[index] == target) {
                    action.accept(baseIndex + index)
                }
            }

            baseIndex += toCopyShorts
            offset += toCopyBytes
        }
    }

    inline fun forEachIndexed(action: (index: Int, value: Short) -> Unit) {
        val size = this.size
        val intSize = 4L

        // 512kb buffer size (262144 shorts * 2 bytes) to align with cpu l2/l3 cache limits and prevent cache thrashing
        val bufferSize = 262144
        val heapBuffer = ShortArray(minOf(size, bufferSize))
        val heapSegment = MemorySegment.ofArray(heapBuffer)

        var offset = intSize
        val endOffset = intSize + (size.toLong() * 2L)
        var baseIndex = 0

        while (offset < endOffset) {
            val toCopyBytes = minOf(heapBuffer.size.toLong() * 2L, endOffset - offset)
            val toCopyShorts = (toCopyBytes / 2L).toInt()

            MemorySegment.copy(segment, offset, heapSegment, 0L, toCopyBytes)

            for (index in 0 until toCopyShorts) {
                action(baseIndex + index, heapBuffer[index])
            }

            baseIndex += toCopyShorts
            offset += toCopyBytes
        }
    }

    override fun pin(): ShortArray {
        val intSize = MemoryLayouts.INT.byteSize()
        return segment.asSlice(intSize, size.toLong() * Short.SIZE_BYTES)
            .toArray(NATIVE_SHORT)
    }

    override fun copy(): ShortArrayView {
        val newSegment = Arena.ofAuto().allocateUninitialized(segment.byteSize())
        MemorySegment.copy(segment, 0L, newSegment, 0L, segment.byteSize())

        return ShortArrayView(newSegment)
    }

    companion object {
        val NATIVE_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.nativeOrder())

        fun of(array: ShortArray, arena: Arena): ShortArrayView {
            val byteSize = MemoryLayouts.INT.byteSize() + (array.size.toLong() * MemoryLayouts.SHORT.byteSize())
            val segment = arena.allocateUninitialized(byteSize)
            segment.set(MemoryLayouts.INT, 0L, array.size)

            val destinationOffset = MemoryLayouts.INT.byteSize()
            MemorySegment.copy(
                MemorySegment.ofArray(array),
                0L,
                segment,
                destinationOffset,
                array.size.toLong() * Short.SIZE_BYTES,
            )

            return ShortArrayView(segment)
        }

        fun of(size: Int, arena: Arena): ShortArrayView {
            val byteSize = MemoryLayouts.INT.byteSize() + (size.toLong() * MemoryLayouts.SHORT.byteSize())
            val segment = arena.allocateUninitialized(byteSize)
            segment.set(MemoryLayouts.INT, 0L, size)

            return ShortArrayView(segment)
        }
    }

}
