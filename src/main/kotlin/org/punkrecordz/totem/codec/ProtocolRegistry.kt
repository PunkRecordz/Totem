package org.punkrecordz.totem.codec

import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey
import java.util.concurrent.ConcurrentHashMap

object ProtocolRegistry {

    @Suppress("UNCHECKED_CAST")
    private val fastProtocolTable: Array<TagProtocol<Tag>?> = arrayOfNulls(16)

    private val customProtocols = ConcurrentHashMap<Int, TagProtocol<*>>()

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
