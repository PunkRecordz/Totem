package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.heap.HeapByteArrayTag
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.MemorySegment

object ByteArrayProtocol : TagProtocol<ByteArrayTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeByteArrayTag {
        val length = segment.get(MemoryLayouts.INT, offset).toLong()
        val totalSize = MemoryLayouts.INT.byteSize() + (length * MemoryLayouts.BYTE.byteSize())

        return NativeByteArrayTag(segment.asSlice(offset, totalSize))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        val length = segment.get(MemoryLayouts.INT, offset).toLong()

        return MemoryLayouts.INT.byteSize() + (length * MemoryLayouts.BYTE.byteSize())
    }

    override fun write(segment: MemorySegment, offset: Long, tag: ByteArrayTag): Long {
        if (tag is NativeByteArrayTag) {
            MemorySegment.copy(tag.segment, 0, segment, offset, tag.sizeInBytes)

            return tag.sizeInBytes
        }

        if (tag is HeapByteArrayTag) {
            val bytes = tag.array
            val payloadSize = bytes.size.toLong() * MemoryLayouts.BYTE.byteSize()

            segment.set(MemoryLayouts.INT, offset, bytes.size)
            MemorySegment.copy(
                MemorySegment.ofArray(bytes),
                0,
                segment,
                offset + MemoryLayouts.INT.byteSize(),
                payloadSize,
            )

            return MemoryLayouts.INT.byteSize() + payloadSize
        }

        val size = tag.size
        val payloadSize = size.toLong() * MemoryLayouts.BYTE.byteSize()

        segment.set(MemoryLayouts.INT, offset, size)
        val intSize = MemoryLayouts.INT.byteSize()

        for (index in 0 until size) {
            segment.set(MemoryLayouts.BYTE, offset + intSize + index.toLong(), tag[index])
        }

        return intSize + payloadSize
    }

    override fun sizeOf(tag: ByteArrayTag): Long = MemoryLayouts.INT.byteSize() + tag.size

}
