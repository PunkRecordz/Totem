package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.impl.heap.HeapCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.view.NativeView
import org.punkrecordz.totem.ffi.TotemSys
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.AbstractMap

private val asciiKeyThreadLocal = ThreadLocal.withInitial { Arena.ofAuto().allocate(256L) }

@JvmInline
value class NativeCompoundTag(
    override val segment: MemorySegment,
) : CompoundTag, NativeView {

    override val size: Int
        get() {
            var count = 0
            forEachRawEntry { _, _, _, _ ->
                count++
            }
            return count
        }

    override fun isEmpty(): Boolean {
        if (segment.byteSize() < MemoryLayouts.BYTE.byteSize()) {
            return true
        }

        return segment.get(MemoryLayouts.BYTE, 0L).toInt() == TagType.END.id
    }

    override fun containsKey(key: String): Boolean = findEntryPacked(key) >= 0L

    override fun containsValue(value: Tag): Boolean {
        forEachRawEntry { _, _, typeId, valueOffset ->
            val protocol = ProtocolRegistry.getOrThrow(typeId)
            if (protocol.read(segment, valueOffset) == value) {
                return true
            }
        }

        return false
    }

    override fun get(key: String): Tag? {
        val packed = findEntryPacked(key)
        if (packed < 0L) return null

        val typeId = (packed ushr 48).toInt()
        val valueOffset = packed and 0x0000FFFFFFFFFFFFL
        val protocol = ProtocolRegistry.getOrThrow(typeId)

        return try {
            protocol.read(segment, valueOffset)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Tag>>
        get() = mutableSetOf<MutableMap.MutableEntry<String, Tag>>().apply {
            forEachEntry { entry ->
                val tag = ProtocolRegistry.getOrThrow(entry.typeId).read(segment, entry.valueOffset)
                add(AbstractMap.SimpleEntry(entry.name, tag))
            }
        }

    override val keys: MutableSet<String>
        get() = mutableSetOf<String>().apply {
            forEachEntry { entry -> add(entry.name) }
        }

    override val values: MutableCollection<Tag>
        get() = mutableListOf<Tag>().apply {
            forEachRawEntry { _, _, typeId, valueOffset ->
                add(ProtocolRegistry.getOrThrow(typeId).read(segment, valueOffset))
            }
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
        forEachRawEntry { nameOffset, _, typeId, valueOffset ->
            val name = MemoryLayouts.readString(segment, nameOffset)
            block(EntryInfo(name, typeId, valueOffset))
        }
    }

    private inline fun forEachRawEntry(
        block: (nameOffset: Long, nameLength: Int, typeId: Int, valueOffset: Long) -> Unit,
    ) {
        var offset = 0L
        val byteSize = MemoryLayouts.BYTE.byteSize()
        val shortSize = MemoryLayouts.SHORT.byteSize()
        val segmentByteSize = segment.byteSize()

        while (offset + byteSize <= segmentByteSize) {
            val typeId = segment.get(MemoryLayouts.BYTE, offset).toInt()
            if (typeId == TagType.END.id) {
                break
            }

            val nameOffset = offset + byteSize
            if (nameOffset + shortSize > segmentByteSize) {
                break
            }

            val nameLength = segment.get(MemoryLayouts.SHORT, nameOffset).toInt() and 0xFFFF
            val valueOffset = nameOffset + shortSize + nameLength

            if (valueOffset > segmentByteSize) {
                break
            }

            val protocol = ProtocolRegistry.getOrThrow(typeId)
            val entrySize = try {
                protocol.calculateSize(segment, valueOffset)
            } catch (_: IndexOutOfBoundsException) {
                break
            }

            if (entrySize <= 0) {
                throw IllegalStateException("Calculated non-positive entry size: $entrySize for tag ID $typeId at offset $valueOffset")
            }

            if (valueOffset + entrySize > segmentByteSize) {
                break
            }

            block(nameOffset, nameLength, typeId, valueOffset)

            offset = valueOffset + entrySize
        }
    }

    private fun findEntryPacked(key: String): Long {
        val characterLength = key.length
        if (characterLength > 65535) {
            return -1L
        }

        val isPureAscii = MemoryLayouts.isAsciiWithoutNull(key)
        if (isPureAscii && characterLength <= 256) {
            val keyBuffer = asciiKeyThreadLocal.get()
            for (index in 0 until characterLength) {
                keyBuffer.set(ValueLayout.JAVA_BYTE, index.toLong(), key[index].code.toByte())
            }

            return TotemSys.findCompoundEntry(segment, 0L, keyBuffer, characterLength.toShort())
        }

        val keyBytes = if (!isPureAscii) {
            try {
                MemoryLayouts.encodeString(key)
            } catch (_: IllegalArgumentException) {
                return -1L
            }
        } else {
            null
        }

        val expectedLength = keyBytes?.size ?: characterLength
        val shortSize = MemoryLayouts.SHORT.byteSize()

        forEachRawEntry { nameOffset, nameLength, typeId, valueOffset ->
            if (nameLength == expectedLength) {
                var matches = true

                for (index in 0 until nameLength) {
                    val segmentByte = segment.get(MemoryLayouts.BYTE, nameOffset + shortSize + index)
                    val expectedByte = keyBytes?.get(index) ?: key[index].code.toByte()

                    if (segmentByte != expectedByte) {
                        matches = false
                        break
                    }
                }

                if (matches) {
                    return (typeId.toLong() shl 48) or (valueOffset and 0x0000FFFFFFFFFFFFL)
                }
            }
        }

        return -1L
    }

    private data class EntryInfo(
        val name: String,
        val typeId: Int,
        val valueOffset: Long,
    )

}
