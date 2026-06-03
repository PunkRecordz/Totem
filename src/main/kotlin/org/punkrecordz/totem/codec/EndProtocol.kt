package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.heap.HeapEndTag
import org.punkrecordz.totem.tag.EndTag
import java.lang.foreign.MemorySegment

object EndProtocol : TagProtocol<EndTag> {

    override fun read(segment: MemorySegment, offset: Long): EndTag {
        return HeapEndTag
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return 0L
    }

    override fun write(segment: MemorySegment, offset: Long, tag: EndTag): Long {
        return 0L
    }

    override fun sizeOf(tag: EndTag): Long = 0L

}
