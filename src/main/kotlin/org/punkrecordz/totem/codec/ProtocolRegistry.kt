package org.punkrecordz.totem.codec

import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

object ProtocolRegistry {

    private val protocols = buildMap<Int, TagProtocol<*>> {
        put(TagType.END.id, EndProtocol)
        put(TagType.BYTE.id, ByteProtocol)
        put(TagType.SHORT.id, ShortProtocol)
        put(TagType.INT.id, IntProtocol)
        put(TagType.LONG.id, LongProtocol)
        put(TagType.FLOAT.id, FloatProtocol)
        put(TagType.DOUBLE.id, DoubleProtocol)
        put(TagType.BYTE_ARRAY.id, ByteArrayProtocol)
        put(TagType.STRING.id, StringProtocol)
        put(TagType.INT_ARRAY.id, IntArrayProtocol)
        put(TagType.LONG_ARRAY.id, LongArrayProtocol)
        put(TagType.LIST.id, ListProtocol)
        put(TagType.COMPOUND.id, CompoundProtocol)
    }.toMutableMap()

    fun register(key: TagKey, protocol: TagProtocol<*>) {
        protocols[key.id] = protocol
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : TagProtocol<*>> get(id: Int): T? {
        return protocols[id] as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun getOrThrow(id: Int): TagProtocol<Tag> {
        return protocols[id] as? TagProtocol<Tag> ?: throw IllegalArgumentException("No protocol found for ID: $id")
    }

}
