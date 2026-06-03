package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapLongArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.LongArrayTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeLongArrayTag(
    override val segment: MemorySegment,
) : LongArrayTag, NativeView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    val sizeInBytes: Long
        get() {
            val intSize = MemoryLayouts.INT.byteSize()

            return intSize + (size.toLong() * 8L)
        }

    override fun get(index: Int): Long {
        val intSize = MemoryLayouts.INT.byteSize()

        return segment.get(
            MemoryLayouts.LONG,
            intSize + (index.toLong() * 8L),
        )
    }

    override fun set(index: Int, value: Long) {
        val intSize = MemoryLayouts.INT.byteSize()

        segment.set(
            MemoryLayouts.LONG,
            intSize + (index.toLong() * 8L),
            value,
        )
    }

    override fun pin(): LongArrayTag {
        val intSize = MemoryLayouts.INT.byteSize()
        val heapArray = segment.asSlice(intSize, size.toLong() * 8L)
            .toArray(MemoryLayouts.LONG)

        return HeapLongArrayTag(heapArray)
    }

    override fun copy(): LongArrayTag = pin()

}
