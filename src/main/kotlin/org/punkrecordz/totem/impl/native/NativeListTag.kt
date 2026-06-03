package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.impl.heap.HeapListTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ListTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeListTag<T : Tag>(
    override val segment: MemorySegment,
) : ListTag<T>, NativeView {

    override val elementType: TagKey
        get() {
            val typeId = segment.get(MemoryLayouts.BYTE, 0L).toInt()
            return TagType.fromId(typeId)
        }

    override val size: Int
        get() {
            val byteSize = MemoryLayouts.BYTE.byteSize()
            return segment.get(MemoryLayouts.INT, byteSize)
        }

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: T): Boolean = any { it == element }

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    override fun get(index: Int): T {
        val typeId = segment.get(MemoryLayouts.BYTE, 0L).toInt()
        if (index !in indices) throw IndexOutOfBoundsException(index)

        val protocol = ProtocolRegistry.getOrThrow(typeId)
        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()

        var offset = headerSize
        repeat(index) {
            offset += protocol.calculateSize(segment, offset)
        }

        @Suppress("UNCHECKED_CAST")
        return protocol.read(segment, offset) as T
    }

    override fun indexOf(element: T): Int {
        forEachIndexed { index, item -> if (item == element) return index }
        return -1
    }

    override fun lastIndexOf(element: T): Int {
        var last = -1
        forEachIndexed { index, item -> if (item == element) last = index }
        return last
    }

    override fun iterator(): MutableIterator<T> {
        val typeId = segment.get(MemoryLayouts.BYTE, 0L).toInt()
        val protocol = ProtocolRegistry.getOrThrow(typeId)
        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()
        val count = size

        return object : MutableIterator<T> {
            private var cursor = 0
            private var currentOffset = headerSize

            override fun hasNext(): Boolean = cursor < count

            override fun next(): T {
                if (cursor >= count) throw NoSuchElementException()
                @Suppress("UNCHECKED_CAST")
                val element = protocol.read(segment, currentOffset) as T
                currentOffset += protocol.calculateSize(segment, currentOffset)
                cursor++
                return element
            }

            override fun remove() = throw UnsupportedOperationException()
        }
    }

    override fun listIterator(): MutableListIterator<T> = listIterator(0)

    override fun listIterator(index: Int): MutableListIterator<T> {
        val typeId = segment.get(MemoryLayouts.BYTE, 0L).toInt()
        val protocol = ProtocolRegistry.getOrThrow(typeId)
        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()

        val count = size
        val offsets = LongArray(count)
        var offset = headerSize
        for (i in 0 until count) {
            offsets[i] = offset
            offset += protocol.calculateSize(segment, offset)
        }

        return object : MutableListIterator<T> {
            private var cursor = index

            override fun hasNext(): Boolean = cursor < count

            override fun next(): T {
                if (cursor >= count) throw NoSuchElementException()
                @Suppress("UNCHECKED_CAST")
                return protocol.read(segment, offsets[cursor++]) as T
            }

            override fun hasPrevious(): Boolean = cursor > 0

            override fun previous(): T {
                if (cursor <= 0) throw NoSuchElementException()
                @Suppress("UNCHECKED_CAST")
                return protocol.read(segment, offsets[--cursor]) as T
            }

            override fun nextIndex(): Int = cursor
            override fun previousIndex(): Int = cursor - 1

            override fun add(element: T) =
                throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

            override fun remove() =
                throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

            override fun set(element: T) =
                throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")
        }
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> {
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")
    }

    override fun add(element: T): Boolean =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun add(index: Int, element: T) =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun addAll(index: Int, elements: Collection<T>): Boolean =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun addAll(elements: Collection<T>): Boolean =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun clear() =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun remove(element: T): Boolean =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun removeAt(index: Int): T =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun removeAll(elements: Collection<T>): Boolean =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun retainAll(elements: Collection<T>): Boolean =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun set(index: Int, element: T): T =
        throw UnsupportedOperationException("NativeListTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun pin(): ListTag<T> {
        val newList = mutableListOf<T>()
        for (item in this) {
            @Suppress("UNCHECKED_CAST")
            newList.add(item.pin() as T)
        }
        return HeapListTag(elementType, newList)
    }

    override fun copy(): ListTag<T> = pin()

}
