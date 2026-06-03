package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeLongTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.LongTag
import java.lang.foreign.MemorySegment

object LongProtocol : TagProtocol<LongTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeLongTag {
        return NativeLongTag(segment.asSlice(offset, 8L))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 8L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: LongTag): Long {
        segment.set(MemoryLayouts.LONG, offset, tag.value)

        return 8L
    }

    override fun sizeOf(tag: LongTag): Long = MemoryLayouts.LONG.byteSize()

}
