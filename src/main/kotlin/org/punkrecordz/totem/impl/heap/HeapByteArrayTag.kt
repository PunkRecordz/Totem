package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.ByteArrayTag
import java.util.function.IntConsumer

class HeapByteArrayTag(
    val array: ByteArray,
) : ByteArrayTag {

    override val size: Int
        get() = array.size

    override fun get(index: Int): Byte {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        return array[index]
    }

    override fun set(index: Int, value: Byte) {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        array[index] = value
    }

    override fun replace(target: Byte, replacement: Byte) {
        for (index in array.indices) {
            if (array[index] == target) {
                array[index] = replacement
            }
        }
    }

    override fun replaceAll(replacements: Map<Byte, Byte>) {
        if (replacements.isEmpty()) {
            return
        }

        val lookupTable = ByteArray(256) { index ->
            index.toByte()
        }

        for ((target, replacement) in replacements) {
            lookupTable[target.toInt() and 0xFF] = replacement
        }

        for (index in array.indices) {
            val unsignedValue = array[index].toInt() and 0xFF
            array[index] = lookupTable[unsignedValue]
        }
    }

    override fun occurrences(): Map<Byte, Int> {
        val frequencyArray = IntArray(256)

        for (index in array.indices) {
            val unsignedValue = array[index].toInt() and 0xFF
            frequencyArray[unsignedValue]++
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
        for (index in array.indices) {
            if (array[index] == target) {
                action.accept(index)
            }
        }
    }

    override fun pin(): ByteArrayTag {
        return HeapByteArrayTag(array.copyOf())
    }

    override fun copy(): HeapByteArrayTag {
        return HeapByteArrayTag(array.copyOf())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteArrayTag) return false

        if (other is HeapByteArrayTag) {
            return array.contentEquals(other.array)
        }

        if (size != other.size) return false
        for (index in 0 until size) {
            if (this[index] != other[index]) return false
        }

        return true
    }

    override fun hashCode(): Int {
        return array.contentHashCode()
    }

    override fun toString(): String {
        return array.joinToString(
            prefix = "[",
            postfix = "]",
            limit = 50,
        )
    }

}
