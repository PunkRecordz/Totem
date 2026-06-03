package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapShortTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ShortTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeShortTag(
    override val segment: MemorySegment,
) : ShortTag, NativeView {

    override val value: Short get() = segment.get(MemoryLayouts.SHORT, 0L)

    override fun pin(): ShortTag {
        return HeapShortTag(value)
    }

    override fun copy(): ShortTag = pin()

}
