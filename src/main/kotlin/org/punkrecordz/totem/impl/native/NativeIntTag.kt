package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapIntTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.IntTag
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeIntTag(
    private val segment: MemorySegment,
) : IntTag {

    override val value: Int get() = segment.get(MemoryLayouts.INT, 0L)

    override fun pin(): IntTag {
        return HeapIntTag(value)
    }

    override fun copy(): IntTag = pin()

}
