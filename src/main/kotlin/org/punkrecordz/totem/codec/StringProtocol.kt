package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeStringTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.StringTag
import java.lang.foreign.MemorySegment

object StringProtocol : TagProtocol<StringTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeStringTag {
        val length = segment.get(MemoryLayouts.SHORT, offset).toInt() and 0xFFFF
        val totalSize = MemoryLayouts.SHORT.byteSize() + length

        return NativeStringTag(segment.asSlice(offset, totalSize))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        val length = segment.get(MemoryLayouts.SHORT, offset).toInt() and 0xFFFF

        return MemoryLayouts.SHORT.byteSize() + length
    }

    override fun write(segment: MemorySegment, offset: Long, tag: StringTag): Long {
        return MemoryLayouts.writeString(segment, offset, tag.value)
    }

    override fun sizeOf(tag: StringTag): Long {
        return MemoryLayouts.SHORT.byteSize() + MemoryLayouts.stringByteLength(tag.value)
    }

}
