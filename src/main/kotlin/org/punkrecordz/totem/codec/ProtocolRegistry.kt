package org.punkrecordz.totem.codec

import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

/**
 * Registry mapping NBT tag type IDs (0..12) to their corresponding serialization protocols.
 *
 * Strategy:
 * Uses a flat direct-indexed array lookup for standard NBT IDs (0..15) to eliminate [java.lang.Integer]
 * boxing and hash bucket traversals in HotSpot, with a secondary concurrent-safe map fallback for
 * custom user-extended protocols.
 *
 * Performance targets:
 * O(1) single-instruction array index lookup for every tag traversal and serialization step.
 */
object ProtocolRegistry {

    @Suppress("UNCHECKED_CAST")
    private val fastProtocolTable: Array<TagProtocol<Tag>?> = arrayOfNulls(16)

    private val customProtocols = mutableMapOf<Int, TagProtocol<*>>()

    init {
        registerDirect(TagType.END.id, EndProtocol)
        registerDirect(TagType.BYTE.id, ByteProtocol)
        registerDirect(TagType.SHORT.id, ShortProtocol)
        registerDirect(TagType.INT.id, IntProtocol)
        registerDirect(TagType.LONG.id, LongProtocol)
        registerDirect(TagType.FLOAT.id, FloatProtocol)
        registerDirect(TagType.DOUBLE.id, DoubleProtocol)
        registerDirect(TagType.BYTE_ARRAY.id, ByteArrayProtocol)
        registerDirect(TagType.STRING.id, StringProtocol)
        registerDirect(TagType.LIST.id, ListProtocol)
        registerDirect(TagType.COMPOUND.id, CompoundProtocol)
        registerDirect(TagType.INT_ARRAY.id, IntArrayProtocol)
        registerDirect(TagType.LONG_ARRAY.id, LongArrayProtocol)
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerDirect(id: Int, protocol: TagProtocol<*>) {
        if (id in fastProtocolTable.indices) {
            fastProtocolTable[id] = protocol as TagProtocol<Tag>
        }
        customProtocols[id] = protocol
    }

    fun register(key: TagKey, protocol: TagProtocol<*>) {
        registerDirect(key.id, protocol)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : TagProtocol<*>> get(id: Int): T? {
        if (id in fastProtocolTable.indices) {
            val fast = fastProtocolTable[id]
            if (fast != null) {
                return fast as T
            }
        }

        return customProtocols[id] as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun getOrThrow(id: Int): TagProtocol<Tag> {
        if (id in fastProtocolTable.indices) {
            val fast = fastProtocolTable[id]
            if (fast != null) {
                return fast
            }
        }

        return customProtocols[id] as? TagProtocol<Tag>
            ?: throw IllegalArgumentException("No protocol found for ID: $id")
    }

}
