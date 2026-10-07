package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeListTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.ListTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.MemorySegment

object ListProtocol : TagProtocol<ListTag<Tag>> {

    override fun read(segment: MemorySegment, offset: Long): NativeListTag<Tag> {
        return NativeListTag(segment.asSlice(offset))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        val typeId = segment.get(MemoryLayouts.BYTE, offset).toInt()
        val count = segment.get(MemoryLayouts.INT, offset + MemoryLayouts.BYTE.byteSize())

        // validate element count
        if (count < 0) {
            throw IllegalArgumentException("ListTag element count must be non-negative, but was $count at offset $offset")
        }

        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()

        if (count == 0) {
            return headerSize
        }

        val protocol = ProtocolRegistry.getOrThrow(typeId)
        var currentOffset = offset + headerSize

        repeat(count) {
            val elementSize = protocol.calculateSize(segment, currentOffset)

            // ensure offset strictly advances
            if (elementSize <= 0) {
                throw IllegalStateException("Calculated non-positive element size: $elementSize for list element type ID $typeId at offset $currentOffset")
            }

            currentOffset += elementSize
        }

        return currentOffset - offset
    }

    override fun write(segment: MemorySegment, offset: Long, tag: ListTag<Tag>): Long {
        val elementType = (tag as? ListTag<*>)?.elementType ?: TagType.END

        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()

        segment.set(MemoryLayouts.BYTE, offset, elementType.id.toByte())
        segment.set(MemoryLayouts.INT, offset + MemoryLayouts.BYTE.byteSize(), tag.size)

        var currentOffset = offset + headerSize

        if (tag.isNotEmpty()) {
            val protocol = ProtocolRegistry.getOrThrow(elementType.id)
            for (element in tag) {
                currentOffset += protocol.write(segment, currentOffset, element)
            }
        }

        return currentOffset - offset
    }

    override fun sizeOf(tag: ListTag<Tag>): Long {
        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()
        if (tag.isEmpty()) return headerSize

        var elementsSize = 0L
        val elementType = tag.elementType
        val protocol = ProtocolRegistry.getOrThrow(elementType.id)

        for (element in tag) {
            elementsSize += protocol.sizeOf(element)
        }

        return headerSize + elementsSize
    }

}
