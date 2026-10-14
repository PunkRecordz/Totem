package org.punkrecordz.totem.codec

import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.impl.native.NativeListTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.ListTag
import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.MemorySegment

object ListProtocol : TagProtocol<ListTag<Tag>> {

    override fun read(segment: MemorySegment, offset: Long): NativeListTag<Tag> {
        return NativeListTag(segment.asSlice(offset))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return TotemSys.calculateListSize(segment, offset)
    }

    override fun write(
        segment: MemorySegment,
        offset: Long,
        tag: ListTag<Tag>,
    ): Long = writeWithDepth(segment, offset, tag, -1L, depth = 0)

    override fun write(
        segment: MemorySegment,
        offset: Long,
        tag: ListTag<Tag>,
        precalculatedSize: Long,
    ): Long = writeWithDepth(segment, offset, tag, precalculatedSize, depth = 0)

    internal fun writeWithDepth(
        segment: MemorySegment,
        offset: Long,
        tag: ListTag<Tag>,
        precalculatedSize: Long,
        depth: Int,
    ): Long {
        if (depth > CompoundProtocol.MAX_NBT_DEPTH) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of ${CompoundProtocol.MAX_NBT_DEPTH}")
        }

        if (tag is NativeListTag) {
            val totalSize = if (precalculatedSize >= 0L) precalculatedSize else calculateSize(tag.segment, 0L)
            MemorySegment.copy(tag.segment, 0L, segment, offset, totalSize)
            return totalSize
        }

        val elementType = tag.elementType

        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()

        segment.set(MemoryLayouts.BYTE, offset, elementType.id.toByte())
        segment.set(MemoryLayouts.INT, offset + MemoryLayouts.BYTE.byteSize(), tag.size)

        var currentOffset = offset + headerSize

        if (tag.isNotEmpty()) {
            if (depth + 1 > CompoundProtocol.MAX_NBT_DEPTH) {
                throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of ${CompoundProtocol.MAX_NBT_DEPTH}")
            }

            val protocol = ProtocolRegistry.getOrThrow(elementType.id)
            for (element in tag) {
                currentOffset += when (element) {
                    is CompoundTag -> CompoundProtocol.writeWithDepth(segment, currentOffset, element, -1L, depth + 1)
                    is ListTag<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        writeWithDepth(segment, currentOffset, element as ListTag<Tag>, -1L, depth + 1)
                    }
                    else -> protocol.write(segment, currentOffset, element)
                }
            }
        }

        return currentOffset - offset
    }

    override fun sizeOf(tag: ListTag<Tag>): Long = sizeOfWithDepth(tag, depth = 0)

    internal fun sizeOfWithDepth(
        tag: ListTag<Tag>,
        depth: Int,
    ): Long {
        if (depth > CompoundProtocol.MAX_NBT_DEPTH) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of ${CompoundProtocol.MAX_NBT_DEPTH}")
        }

        if (tag is NativeListTag) {
            return calculateSize(tag.segment, 0L)
        }

        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.INT.byteSize()
        if (tag.isEmpty()) return headerSize

        if (depth + 1 > CompoundProtocol.MAX_NBT_DEPTH) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of ${CompoundProtocol.MAX_NBT_DEPTH}")
        }

        val elementType = tag.elementType
        val protocol = ProtocolRegistry.getOrThrow(elementType.id)
        val fixedElementSize = protocol.fixedSize

        if (fixedElementSize != null) {
            return headerSize + (tag.size.toLong() * fixedElementSize)
        }

        var elementsSize = 0L
        for (element in tag) {
            elementsSize += when (element) {
                is CompoundTag -> CompoundProtocol.sizeOfWithDepth(element, depth + 1)
                is ListTag<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    sizeOfWithDepth(element as ListTag<Tag>, depth + 1)
                }
                else -> protocol.sizeOf(element)
            }
        }

        return headerSize + elementsSize
    }

}

