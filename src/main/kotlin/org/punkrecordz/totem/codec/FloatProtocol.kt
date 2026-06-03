package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeFloatTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.FloatTag
import java.lang.foreign.MemorySegment

object FloatProtocol : TagProtocol<FloatTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeFloatTag {
        return NativeFloatTag(segment.asSlice(offset, 4L))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 4L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: FloatTag): Long {
        segment.set(MemoryLayouts.FLOAT, offset, tag.value)

        return 4L
    }

    override fun sizeOf(tag: FloatTag): Long = MemoryLayouts.FLOAT.byteSize()

}
