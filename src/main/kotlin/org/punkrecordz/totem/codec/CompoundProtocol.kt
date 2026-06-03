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
            currentOffset += protocol.calculateSize(segment, currentOffset)
        }

        return currentOffset - offset
    }

    override fun write(segment: MemorySegment, offset: Long, tag: CompoundTag): Long {
        var currentOffset = offset

        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()

        for ((key, value) in tag) {
            val tagValue = value

            segment.set(MemoryLayouts.BYTE, currentOffset, tagValue.key.id.toByte())
            currentOffset += byteSize

            var isAscii = true
            for (index in key.indices) {
                if (key[index].code >= 128) {
                    isAscii = false
                    break
                }
            }

            if (isAscii) {
                segment.set(MemoryLayouts.SHORT, currentOffset, key.length.toShort())
                currentOffset += shortSize

                for (index in key.indices) {
                    segment.set(MemoryLayouts.BYTE, currentOffset + index, key[index].code.toByte())
                }
                currentOffset += key.length
            } else {
                val nameBytes = key.toByteArray(Charsets.UTF_8)

                segment.set(MemoryLayouts.SHORT, currentOffset, nameBytes.size.toShort())
                currentOffset += shortSize

                MemorySegment.copy(MemorySegment.ofArray(nameBytes), 0, segment, currentOffset, nameBytes.size.toLong())
                currentOffset += nameBytes.size
            }

            val protocol = ProtocolRegistry.getOrThrow(tagValue.key.id)
            currentOffset += protocol.write(segment, currentOffset, tagValue)
        }

        segment.set(MemoryLayouts.BYTE, currentOffset, TagType.END.id.toByte())
        currentOffset += byteSize

        return currentOffset - offset
    }

    override fun sizeOf(tag: CompoundTag): Long {
        var totalSize = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()

        for ((key, value) in tag) {
            totalSize += byteSize

            var isAscii = true
            for (index in key.indices) {
                if (key[index].code >= 128) {
                    isAscii = false
                    break
                }
            }

            val nameBytesLength = if (isAscii) {
                key.length
            } else {
                key.toByteArray(Charsets.UTF_8).size
            }

            totalSize += shortSize + nameBytesLength
            val protocol = ProtocolRegistry.getOrThrow(value.key.id)
            totalSize += protocol.sizeOf(value)
        }
        totalSize += byteSize
        return totalSize
    }

}
