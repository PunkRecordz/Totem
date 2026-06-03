package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapDoubleTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.DoubleTag
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeDoubleTag(
    private val segment: MemorySegment,
) : DoubleTag {

    override val value: Double get() = segment.get(MemoryLayouts.DOUBLE, 0L)

    override fun pin(): DoubleTag {
        return HeapDoubleTag(value)
    }

    override fun copy(): DoubleTag = pin()

}
