package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeIntTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.IntTag
import java.lang.foreign.MemorySegment

object IntProtocol : TagProtocol<IntTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeIntTag {
        return NativeIntTag(segment.asSlice(offset, 4L))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 4L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: IntTag): Long {
        segment.set(MemoryLayouts.INT, offset, tag.value)

        return 4L
    }

    override fun sizeOf(tag: IntTag): Long = MemoryLayouts.INT.byteSize()

}
