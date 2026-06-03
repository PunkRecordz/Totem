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
        val stringValue = tag.value
        val shortSize = MemoryLayouts.SHORT.byteSize()

        var isAscii = true
        for (index in stringValue.indices) {
            if (stringValue[index].code >= 128) {
                isAscii = false
                break
            }
        }

        if (isAscii) {
            segment.set(MemoryLayouts.SHORT, offset, stringValue.length.toShort())
            for (index in stringValue.indices) {
                segment.set(MemoryLayouts.BYTE, offset + shortSize + index, stringValue[index].code.toByte())
            }
            return shortSize + stringValue.length
        } else {
            val bytes = stringValue.toByteArray(Charsets.UTF_8)
            segment.set(MemoryLayouts.SHORT, offset, bytes.size.toShort())
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, offset + shortSize, bytes.size.toLong())
            return shortSize + bytes.size
        }
    }

    override fun sizeOf(tag: StringTag): Long {
        val stringValue = tag.value

        var isAscii = true
        for (index in stringValue.indices) {
            if (stringValue[index].code >= 128) {
                isAscii = false
                break
            }
        }

        val bytesLength = if (isAscii) {
            stringValue.length.toLong()
        } else {
            stringValue.toByteArray(Charsets.UTF_8).size.toLong()
        }

        return MemoryLayouts.SHORT.byteSize() + bytesLength
    }

}
