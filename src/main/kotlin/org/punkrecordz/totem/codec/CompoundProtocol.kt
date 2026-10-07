package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import java.lang.foreign.MemorySegment

object CompoundProtocol : TagProtocol<CompoundTag> {

    override fun read(segment: MemorySegment, offset: Long): NativeCompoundTag {
        return NativeCompoundTag(segment.asSlice(offset))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        var currentOffset = offset
        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()

        while (true) {
            val typeId = segment.get(MemoryLayouts.BYTE, currentOffset).toInt()
            currentOffset += byteSize

            if (typeId == TagType.END.id) {
                break
            }

            val nameLength = segment.get(MemoryLayouts.SHORT, currentOffset).toInt() and 0xFFFF
            currentOffset += shortSize + nameLength

            val protocol = ProtocolRegistry.getOrThrow(typeId)
            val entrySize = protocol.calculateSize(segment, currentOffset)

            // ensure offset strictly advances to prevent infinite traversal loops
            if (entrySize <= 0) {
                throw IllegalStateException("Calculated non-positive entry size: $entrySize for tag ID $typeId at offset $currentOffset")
            }

            currentOffset += entrySize
        }

        return currentOffset - offset
    }

    override fun write(segment: MemorySegment, offset: Long, tag: CompoundTag): Long {
        var currentOffset = offset
        val byteSize = MemoryLayouts.BYTE.byteSize()

        for ((key, value) in tag) {
            segment.set(MemoryLayouts.BYTE, currentOffset, value.key.id.toByte())
            currentOffset += byteSize

            currentOffset += MemoryLayouts.writeString(segment, currentOffset, key)

            val protocol = ProtocolRegistry.getOrThrow(value.key.id)
            currentOffset += protocol.write(segment, currentOffset, value)
        }

        segment.set(MemoryLayouts.BYTE, currentOffset, TagType.END.id.toByte())
        currentOffset += byteSize

        return currentOffset - offset
    }

    override fun sizeOf(tag: CompoundTag): Long {
        var totalSize = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()

        for ((key, value) in tag) {
            totalSize += byteSize
            totalSize += MemoryLayouts.SHORT.byteSize() + MemoryLayouts.stringByteLength(key)

            val protocol = ProtocolRegistry.getOrThrow(value.key.id)
            totalSize += protocol.sizeOf(value)
        }

        totalSize += byteSize
        return totalSize
    }

}
