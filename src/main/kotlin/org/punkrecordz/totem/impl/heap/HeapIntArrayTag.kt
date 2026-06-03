package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.IntArrayTag
import org.punkrecordz.totem.view.IntView

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

    override fun pin(): IntArrayTag {
        return HeapIntArrayTag(array.copyOf())
    }

    override fun copy(): HeapIntArrayTag {
        return HeapIntArrayTag(array.copyOf())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntView) return false
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
