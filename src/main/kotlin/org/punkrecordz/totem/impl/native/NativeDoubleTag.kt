package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapDoubleTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.DoubleTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeDoubleTag(
    override val segment: MemorySegment,
) : DoubleTag, NativeView {

    override val value: Double get() = segment.get(MemoryLayouts.DOUBLE, 0L)

    override fun pin(): DoubleTag {
        return HeapDoubleTag(value)
    }

    override fun copy(): DoubleTag = pin()

}
