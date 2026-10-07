package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.impl.heap.HeapCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment
import java.util.*

@JvmInline
value class NativeCompoundTag(
    override val segment: MemorySegment,
) : CompoundTag, NativeView {

    override val size: Int
        get() {
            var count = 0
            forEachEntry { count++ }
            return count
        }

    override fun isEmpty(): Boolean {
        return segment.get(MemoryLayouts.BYTE, 0L).toInt() == TagType.END.id
    }

    override fun containsKey(key: String): Boolean = findEntry(key) != null

    override fun containsValue(value: Tag): Boolean {
        var found = false
        forEachEntry { entry ->
            val protocol = ProtocolRegistry.getOrThrow(entry.typeId)
            val tag = protocol.read(segment, entry.valueOffset)
            if (tag == value) {
                found = true
            }
        }
        return found
    }

    override fun get(key: String): Tag? {
        val entry = findEntry(key) ?: return null
        val protocol = ProtocolRegistry.getOrThrow(entry.typeId)

        return protocol.read(segment, entry.valueOffset)
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Tag>>
        get() {
            val set = mutableSetOf<MutableMap.MutableEntry<String, Tag>>()
            forEachEntry { entry ->
                val protocol = ProtocolRegistry.getOrThrow(entry.typeId)
                val tag = protocol.read(segment, entry.valueOffset)
                set.add(AbstractMap.SimpleEntry(entry.name, tag))
            }
            return set
        }

    override val keys: MutableSet<String>
        get() {
            val set = mutableSetOf<String>()
            forEachEntry { set.add(it.name) }
            return set
        }

    override val values: MutableCollection<Tag>
        get() {
            val list = mutableListOf<Tag>()
            forEachEntry { entry ->
                val protocol = ProtocolRegistry.getOrThrow(entry.typeId)
                list.add(protocol.read(segment, entry.valueOffset))
            }
            return list
        }

    override fun clear() =
        throw UnsupportedOperationException("NativeCompoundTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun put(key: String, value: Tag): Tag =
        throw UnsupportedOperationException("NativeCompoundTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun putAll(from: Map<out String, Tag>) =
        throw UnsupportedOperationException("NativeCompoundTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun remove(key: String): Tag =
        throw UnsupportedOperationException("NativeCompoundTag is an immutable view over a MemorySegment and cannot be modified.")

    override fun pin(): CompoundTag {
        val map = mutableMapOf<String, Tag>()
        forEachEntry { entry ->
            val protocol = ProtocolRegistry.getOrThrow(entry.typeId)
            map[entry.name] = protocol.read(segment, entry.valueOffset).pin()
        }
        return HeapCompoundTag(map)
    }

    override fun copy(): CompoundTag = pin()

    private inline fun forEachEntry(block: (EntryInfo) -> Unit) {
        var offset = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()

        while (true) {
            val typeId = segment.get(MemoryLayouts.BYTE, offset).toInt()
            if (typeId == TagType.END.id) break

            offset += byteSize
            val nameLength = segment.get(MemoryLayouts.SHORT, offset).toInt() and 0xFFFF
            val name = MemoryLayouts.readString(segment, offset)
            val valueOffset = offset + shortSize + nameLength

            block(EntryInfo(name, typeId, valueOffset))

            val protocol = ProtocolRegistry.getOrThrow(typeId)
            val entrySize = protocol.calculateSize(segment, valueOffset)

            // ensure offset strictly advances to prevent infinite traversal loops
            if (entrySize <= 0) {
                throw IllegalStateException("Calculated non-positive entry size: $entrySize for tag ID $typeId at offset $valueOffset")
            }

            offset = valueOffset + entrySize
        }
    }

    private fun findEntry(key: String): EntryInfo? {
        val characterLength = key.length
        if (characterLength > 65535) {
            return null
        }

        val isPureAscii = MemoryLayouts.isAsciiWithoutNull(key)
        val keyBytes = if (!isPureAscii) {
            try {
                MemoryLayouts.encodeString(key)
            } catch (_: IllegalArgumentException) {
                return null
            }
        } else {
            null
        }

        val expectedLength = keyBytes?.size ?: characterLength
        var offset = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()

        while (true) {
            val typeId = segment.get(MemoryLayouts.BYTE, offset).toInt()
            if (typeId == TagType.END.id) {
                break
            }

            offset += byteSize
            val nameLength = segment.get(MemoryLayouts.SHORT, offset).toInt() and 0xFFFF
            val valueOffset = offset + shortSize + nameLength

            if (nameLength == expectedLength) {
                var matches = true

                for (index in 0 until nameLength) {
                    val segmentByte = segment.get(MemoryLayouts.BYTE, offset + shortSize + index)
                    val expectedByte = keyBytes?.get(index) ?: key[index].code.toByte()

                    if (segmentByte != expectedByte) {
                        matches = false
                        break
                    }
                }

                if (matches) {
                    return EntryInfo(
                        name = key,
                        typeId = typeId,
                        valueOffset = valueOffset,
                    )
                }
            }

            val protocol = ProtocolRegistry.getOrThrow(typeId)
            val entrySize = protocol.calculateSize(segment, valueOffset)

            if (entrySize <= 0) {
                throw IllegalStateException("Calculated non-positive entry size: $entrySize for tag ID $typeId at offset $valueOffset")
            }

            offset = valueOffset + entrySize
        }

        return null
    }

    private data class EntryInfo(
        val name: String,
        val typeId: Int,
        val valueOffset: Long,
    )

}

