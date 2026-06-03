package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.impl.heap.HeapCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.MemorySegment
import java.util.*

@JvmInline
value class NativeCompoundTag(
    private val segment: MemorySegment,
) : CompoundTag {

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
            offset = valueOffset + protocol.calculateSize(segment, valueOffset)
        }
    }

    private fun findEntry(key: String): EntryInfo? {
        var isAscii = true
        for (index in key.indices) {
            if (key[index].code >= 128) {
                isAscii = false
                break
            }
        }

        if (isAscii) {
            val keyLength = key.length
            var offset = 0L
            val byteSize = MemoryLayouts.BYTE.byteSize()
            val shortSize = MemoryLayouts.SHORT.byteSize()

            while (true) {
                val typeId = segment.get(MemoryLayouts.BYTE, offset).toInt()
                if (typeId == TagType.END.id) break

                offset += byteSize
                val nameLength = segment.get(MemoryLayouts.SHORT, offset).toInt() and 0xFFFF

                if (nameLength == keyLength) {
                    var match = true
                    for (index in 0 until nameLength) {
                        val segmentByte = segment.get(MemoryLayouts.BYTE, offset + shortSize + index)
                        if (segmentByte != key[index].code.toByte()) {
                            match = false
                            break
                        }
                    }

                    if (match) {
                        val valueOffset = offset + shortSize + nameLength
                        return EntryInfo(
                            name = key,
                            typeId = typeId,
                            valueOffset = valueOffset,
                        )
                    }
                }

                val valueOffset = offset + shortSize + nameLength
                val protocol = ProtocolRegistry.getOrThrow(typeId)
                offset = valueOffset + protocol.calculateSize(segment, valueOffset)
            }

            return null
        }

        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val keyLength = keyBytes.size
        var offset = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()

        while (true) {
            val typeId = segment.get(MemoryLayouts.BYTE, offset).toInt()
            if (typeId == TagType.END.id) break

            offset += byteSize
            val nameLength = segment.get(MemoryLayouts.SHORT, offset).toInt() and 0xFFFF

            if (nameLength == keyLength) {
                var match = true
                for (index in 0 until nameLength) {
                    if (segment.get(MemoryLayouts.BYTE, offset + shortSize + index) != keyBytes[index]) {
                        match = false
                        break
                    }
                }

                if (match) {
                    val valueOffset = offset + shortSize + nameLength
                    return EntryInfo(
                        name = key,
                        typeId = typeId,
                        valueOffset = valueOffset,
                    )
                }
            }

            val valueOffset = offset + shortSize + nameLength
            val protocol = ProtocolRegistry.getOrThrow(typeId)
            offset = valueOffset + protocol.calculateSize(segment, valueOffset)
        }

        return null
    }

    private data class EntryInfo(
        val name: String,
        val typeId: Int,
        val valueOffset: Long,
    )

}

