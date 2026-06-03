package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeDoubleTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.DoubleTag
import java.lang.foreign.MemorySegment

object DoubleProtocol : TagProtocol<DoubleTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeDoubleTag {
        return NativeDoubleTag(segment.asSlice(offset, 8L))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 8L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: DoubleTag): Long {
        segment.set(MemoryLayouts.DOUBLE, offset, tag.value)

        return 8L
    }

    override fun sizeOf(tag: DoubleTag): Long = MemoryLayouts.DOUBLE.byteSize()

}
