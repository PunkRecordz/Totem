package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapStringTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.StringTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeStringTag(
    override val segment: MemorySegment,
) : StringTag, NativeView {

    override val value: String get() = MemoryLayouts.readString(segment)

    override fun pin(): StringTag {
        return HeapStringTag(value)
    }

    override fun copy(): StringTag = pin()

}
