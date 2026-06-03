package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeShortTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ShortTag
import java.lang.foreign.MemorySegment

object ShortProtocol : TagProtocol<ShortTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeShortTag {
        return NativeShortTag(segment.asSlice(offset, 2L))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 2L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: ShortTag): Long {
        segment.set(MemoryLayouts.SHORT, offset, tag.value)

        return 2L
    }

    override fun sizeOf(tag: ShortTag): Long = MemoryLayouts.SHORT.byteSize()

}
