package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapFloatTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.FloatTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeFloatTag(
    override val segment: MemorySegment,
) : FloatTag, NativeView {

    override val value: Float get() = segment.get(MemoryLayouts.FLOAT, 0L)

    override fun pin(): FloatTag {
        return HeapFloatTag(value)
    }

    override fun copy(): FloatTag = pin()

}
