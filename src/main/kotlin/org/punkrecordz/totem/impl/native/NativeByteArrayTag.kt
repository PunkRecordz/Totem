package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapByteArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ByteArrayTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeByteArrayTag(
    override val segment: MemorySegment,
) : ByteArrayTag, NativeView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    val sizeInBytes: Long
        get() = segment.byteSize()

    override fun get(index: Int): Byte {
        val intSize = MemoryLayouts.INT.byteSize()

        return segment.get(MemoryLayouts.BYTE, intSize + index.toLong())
    }

    override fun set(index: Int, value: Byte) {
        val intSize = MemoryLayouts.INT.byteSize()

        segment.set(MemoryLayouts.BYTE, intSize + index.toLong(), value)
    }

    override fun pin(): ByteArrayTag {
        val intSize = MemoryLayouts.INT.byteSize()
        val heapArray = segment.asSlice(intSize, size.toLong()).toArray(MemoryLayouts.BYTE)

        return HeapByteArrayTag(heapArray)
    }

    override fun copy(): ByteArrayTag = pin()

}
