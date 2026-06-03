package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.heap.HeapLongArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.LongArrayTag
import java.lang.foreign.MemorySegment

object LongArrayProtocol : TagProtocol<LongArrayTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeLongArrayTag {
        val length = segment.get(MemoryLayouts.INT, offset).toLong()
        val totalSize = MemoryLayouts.INT.byteSize() + (length * MemoryLayouts.LONG.byteSize())

        return NativeLongArrayTag(segment.asSlice(offset, totalSize))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        val length = segment.get(MemoryLayouts.INT, offset).toLong()

        return MemoryLayouts.INT.byteSize() + (length * MemoryLayouts.LONG.byteSize())
    }

    override fun write(segment: MemorySegment, offset: Long, tag: LongArrayTag): Long {
        if (tag is NativeLongArrayTag) {
            MemorySegment.copy(tag.segment, 0, segment, offset, tag.sizeInBytes)

            return tag.sizeInBytes
        }

        if (tag is HeapLongArrayTag) {
            val array = tag.array
            val intSize = MemoryLayouts.INT.byteSize()
            val longSize = MemoryLayouts.LONG.byteSize()

            segment.set(MemoryLayouts.INT, offset, array.size)
            for (index in array.indices) {
                val elementOffset = offset + intSize + (index.toLong() * longSize)
                segment.set(MemoryLayouts.LONG, elementOffset, array[index])
            }

            return intSize + (array.size.toLong() * longSize)
        }

        val size = tag.size
        val intSize = MemoryLayouts.INT.byteSize()
        val longSize = MemoryLayouts.LONG.byteSize()

        segment.set(MemoryLayouts.INT, offset, size)
        for (index in 0 until size) {
            val elementOffset = offset + intSize + (index.toLong() * longSize)
            segment.set(MemoryLayouts.LONG, elementOffset, tag[index])
        }

        return intSize + (size.toLong() * longSize)
    }

    override fun sizeOf(tag: LongArrayTag): Long =
        MemoryLayouts.INT.byteSize() + (tag.size * MemoryLayouts.LONG.byteSize())

}
