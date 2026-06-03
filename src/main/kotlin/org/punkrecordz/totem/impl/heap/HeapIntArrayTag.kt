package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.IntArrayTag
import java.util.function.IntConsumer

class HeapIntArrayTag(
    val array: IntArray,
) : IntArrayTag {

    override val size: Int
        get() = array.size

    override fun get(index: Int): Int {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        return array[index]
    }

    override fun set(index: Int, value: Int) {
        if (index !in 0..<size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }

        array[index] = value
    }

    override fun replace(target: Int, replacement: Int) {
        for (index in array.indices) {
            if (array[index] == target) {
                array[index] = replacement
            }
        }
    }


    override fun replaceAll(replacements: Map<Int, Int>) {
        if (replacements.isEmpty()) {
            return
        }

        val capacity = 1 shl (32 - Integer.numberOfLeadingZeros(replacements.size * 2))
        val mask = capacity - 1
        val keys = IntArray(capacity)
        val values = IntArray(capacity)
        val hasValue = BooleanArray(capacity)

        for ((target, replacement) in replacements) {
            var slot = target.hashCode() and mask
            while (hasValue[slot]) {
                if (keys[slot] == target) {
                    break
                }
                slot = (slot + 1) and mask
            }
            keys[slot] = target
            values[slot] = replacement
            hasValue[slot] = true
        }

        for (index in array.indices) {
            val element = array[index]
            var slot = element.hashCode() and mask
            while (hasValue[slot]) {
                if (keys[slot] == element) {
                    array[index] = values[slot]
                    break
                }
                slot = (slot + 1) and mask
            }
        }
    }

    override fun occurrences(): Map<Int, Int> {
        val result = HashMap<Int, Int>()

        for (index in array.indices) {
            val element = array[index]
            result.merge(element, 1) { old, new -> old + new }
        }

        return result
    }

    override fun forEachIndex(target: Int, action: IntConsumer) {
        for (index in array.indices) {
            if (array[index] == target) {
                action.accept(index)
            }
        }
    }

    override fun pin(): IntArrayTag {
        return HeapIntArrayTag(array.copyOf())
    }

    override fun copy(): HeapIntArrayTag {
        return HeapIntArrayTag(array.copyOf())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntArrayTag) return false

        if (other is HeapIntArrayTag) {
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
