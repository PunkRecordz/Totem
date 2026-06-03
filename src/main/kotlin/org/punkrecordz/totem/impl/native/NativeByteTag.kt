package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapByteTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ByteTag
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeByteTag(
    private val segment: MemorySegment,
) : ByteTag {

    override val value: Byte get() = segment.get(MemoryLayouts.BYTE, 0L)

    override fun pin(): ByteTag {
        return HeapByteTag(value)
    }

    override fun copy(): ByteTag = pin()

}
