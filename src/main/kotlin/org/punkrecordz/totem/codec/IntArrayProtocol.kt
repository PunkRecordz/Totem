package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.heap.HeapIntArrayTag
import org.punkrecordz.totem.impl.native.NativeIntArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.IntArrayTag
import java.lang.foreign.MemorySegment

object IntArrayProtocol : TagProtocol<IntArrayTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeIntArrayTag {
        val length = segment.get(MemoryLayouts.INT, offset).toLong()
        val totalSize = MemoryLayouts.INT.byteSize() + (length * MemoryLayouts.INT.byteSize())

        return NativeIntArrayTag(segment.asSlice(offset, totalSize))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        val length = segment.get(MemoryLayouts.INT, offset).toLong()

        return MemoryLayouts.INT.byteSize() + (length * MemoryLayouts.INT.byteSize())
    }

    override fun write(segment: MemorySegment, offset: Long, tag: IntArrayTag): Long {
        if (tag is NativeIntArrayTag) {
            MemorySegment.copy(tag.segment, 0, segment, offset, tag.sizeInBytes)

            return tag.sizeInBytes
        }

        if (tag is HeapIntArrayTag) {
            val array = tag.array
            val intSize = MemoryLayouts.INT.byteSize()

            segment.set(MemoryLayouts.INT, offset, array.size)
            for (index in array.indices) {
                val elementOffset = offset + intSize + (index.toLong() * intSize)
                segment.set(MemoryLayouts.INT, elementOffset, array[index])
            }

            return intSize + (array.size.toLong() * intSize)
        }

        val size = tag.size
        val intSize = MemoryLayouts.INT.byteSize()

        segment.set(MemoryLayouts.INT, offset, size)
        for (index in 0 until size) {
            val elementOffset = offset + intSize + (index.toLong() * intSize)
            segment.set(MemoryLayouts.INT, elementOffset, tag[index])
        }

        return intSize + (size.toLong() * intSize)
    }

    override fun sizeOf(tag: IntArrayTag): Long =
        MemoryLayouts.INT.byteSize() + (tag.size * MemoryLayouts.INT.byteSize())

}
