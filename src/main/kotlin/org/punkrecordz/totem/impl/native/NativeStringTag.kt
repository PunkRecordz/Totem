package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapStringTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.StringTag
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeStringTag(
    private val segment: MemorySegment,
) : StringTag {

    override val value: String get() = MemoryLayouts.readString(segment)

    override fun pin(): StringTag {
        return HeapStringTag(value)
    }

    override fun copy(): StringTag = pin()

}
