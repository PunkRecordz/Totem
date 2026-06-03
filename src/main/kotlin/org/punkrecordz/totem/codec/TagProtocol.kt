package org.punkrecordz.totem.codec

import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.MemorySegment

interface TagProtocol<T : Tag> {

    fun read(segment: MemorySegment, offset: Long): T

    fun calculateSize(segment: MemorySegment, offset: Long): Long

    fun write(segment: MemorySegment, offset: Long, tag: T): Long

    fun sizeOf(tag: T): Long

}
