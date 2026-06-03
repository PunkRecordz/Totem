package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeByteTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ByteTag
import java.lang.foreign.MemorySegment

object ByteProtocol : TagProtocol<ByteTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeByteTag {
        return NativeByteTag(segment.asSlice(offset, 1L))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 1L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: ByteTag): Long {
        segment.set(MemoryLayouts.BYTE, offset, tag.value)

        return 1L
    }

    override fun sizeOf(tag: ByteTag): Long = MemoryLayouts.BYTE.byteSize()

}
