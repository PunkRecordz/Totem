package org.punkrecordz.totem.codec

import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.impl.native.NativeCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.ListTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.MemorySegment

object CompoundProtocol : TagProtocol<CompoundTag> {

    internal const val MAX_NBT_DEPTH: Int = 512

    override fun read(segment: MemorySegment, offset: Long): NativeCompoundTag {
        return NativeCompoundTag(segment.asSlice(offset))
    }

    override fun calculateSize(segment: MemorySegment, offset: Long): Long {
        return TotemSys.calculateCompoundSize(segment, offset)
    }

    override fun write(
        segment: MemorySegment,
        offset: Long,
        tag: CompoundTag,
    ): Long = writeWithDepth(segment, offset, tag, -1L, depth = 0)

    override fun write(
        segment: MemorySegment,
        offset: Long,
        tag: CompoundTag,
        precalculatedSize: Long,
    ): Long = writeWithDepth(segment, offset, tag, precalculatedSize, depth = 0)

    internal fun writeWithDepth(
        segment: MemorySegment,
        offset: Long,
        tag: CompoundTag,
        precalculatedSize: Long,
        depth: Int,
    ): Long {
        if (depth > MAX_NBT_DEPTH) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of $MAX_NBT_DEPTH")
        }

        if (tag is NativeCompoundTag) {
            val totalSize = if (precalculatedSize >= 0L) precalculatedSize else calculateSize(tag.segment, 0L)
            MemorySegment.copy(tag.segment, 0L, segment, offset, totalSize)
            return totalSize
        }

        var currentOffset = offset
        val byteSize = MemoryLayouts.BYTE.byteSize()

        for ((tagKey, tagValue) in tag) {
            segment.set(MemoryLayouts.BYTE, currentOffset, tagValue.key.id.toByte())
            currentOffset += byteSize

            currentOffset += MemoryLayouts.writeString(segment, currentOffset, tagKey)

            currentOffset += when (tagValue) {
                is CompoundTag -> writeWithDepth(segment, currentOffset, tagValue, -1L, depth + 1)
                is ListTag<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    ListProtocol.writeWithDepth(segment, currentOffset, tagValue as ListTag<Tag>, -1L, depth + 1)
                }
                else -> {
                    val protocol = ProtocolRegistry.getOrThrow(tagValue.key.id)
                    protocol.write(segment, currentOffset, tagValue)
                }
            }
        }

        segment.set(MemoryLayouts.BYTE, currentOffset, TagType.END.id.toByte())
        currentOffset += byteSize

        return currentOffset - offset
    }

    override fun sizeOf(tag: CompoundTag): Long = sizeOfWithDepth(tag, depth = 0)

    internal fun sizeOfWithDepth(
        tag: CompoundTag,
        depth: Int,
    ): Long {
        if (depth > MAX_NBT_DEPTH) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of $MAX_NBT_DEPTH")
        }

        if (tag is NativeCompoundTag) {
            return calculateSize(tag.segment, 0L)
        }

        var totalSize = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()

        for ((tagKey, tagValue) in tag) {
            totalSize += byteSize
            totalSize += MemoryLayouts.SHORT.byteSize() + MemoryLayouts.stringByteLength(tagKey)

            totalSize += when (tagValue) {
                is CompoundTag -> sizeOfWithDepth(tagValue, depth + 1)
                is ListTag<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    ListProtocol.sizeOfWithDepth(tagValue as ListTag<Tag>, depth + 1)
                }
                else -> {
                    val protocol = ProtocolRegistry.getOrThrow(tagValue.key.id)
                    protocol.sizeOf(tagValue)
                }
            }
        }

        totalSize += byteSize
        return totalSize
    }

}

