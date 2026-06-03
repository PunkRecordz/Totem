package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.impl.heap.HeapLongTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.LongTag
import java.lang.foreign.MemorySegment

@JvmInline
value class NativeLongTag(
    private val segment: MemorySegment,
) : LongTag {

    override val value: Long get() = segment.get(MemoryLayouts.LONG, 0L)

    override fun pin(): LongTag {
        return HeapLongTag(value)
    }

    override fun copy(): LongTag = pin()

}
