package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapIntArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.IntArrayTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeIntArrayTag(
    override val segment: MemorySegment,
) : IntArrayTag, NativeView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    val sizeInBytes: Long
        get() = segment.byteSize()

    override fun get(index: Int): Int {
        val intSize = MemoryLayouts.INT.byteSize()

        return segment.get(
            MemoryLayouts.INT,
            intSize + (index.toLong() * 4L),
        )
    }

    override fun set(index: Int, value: Int) {
        val intSize = MemoryLayouts.INT.byteSize()

        segment.set(
            MemoryLayouts.INT,
            intSize + (index.toLong() * 4L),
            value,
        )
    }

    override fun pin(): IntArrayTag {
        val intSize = MemoryLayouts.INT.byteSize()
        val heapArray = segment.asSlice(intSize, size.toLong() * 4L)
            .toArray(MemoryLayouts.INT)

        return HeapIntArrayTag(heapArray)
    }

    override fun copy(): IntArrayTag = pin()

}
