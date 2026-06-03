package org.punkrecordz.totem.tag

import org.punkrecordz.totem.impl.heap.*
import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

interface CompoundTag : Tag, PinnableTag<CompoundTag>, MutableMap<String, Tag> {

    override val key: TagKey get() = TagType.COMPOUND


    fun getNumber(key: String): Number? {
        val tag = this[key] as? NumericTag<*>
        return tag?.value
    }

    fun getByte(key: String): Byte? = getNumber(key)?.toByte()

    fun getShort(key: String): Short? = getNumber(key)?.toShort()

    fun getInt(key: String): Int? = getNumber(key)?.toInt()

    fun getLong(key: String): Long? = getNumber(key)?.toLong()

    fun getFloat(key: String): Float? = getNumber(key)?.toFloat()

    fun getDouble(key: String): Double? = getNumber(key)?.toDouble()

    fun getString(key: String): String? = (this[key] as? StringTag)?.value

    fun getByteArray(key: String): ByteArrayTag? = this[key] as? ByteArrayTag

    fun getIntArray(key: String): IntArrayTag? = this[key] as? IntArrayTag

    fun getLongArray(key: String): LongArrayTag? = this[key] as? LongArrayTag

    fun getCompound(key: String): CompoundTag? = this[key] as? CompoundTag

    fun getList(key: String): ListTag<*>? = this[key] as? ListTag<*>

    @Suppress("UNCHECKED_CAST")
    fun <T : Tag> getList(key: String, expectedElementType: TagKey): ListTag<T>? {
        val tag = this[key] as? ListTag<*> ?: return null

        require(tag.elementType == expectedElementType) {
            "Expected list of $expectedElementType under key '$key', but found list of ${tag.elementType}"
        }

        return tag as ListTag<T>
    }

    fun putByte(key: String, value: Byte): Tag? = put(key, HeapByteTag(value))

    fun putShort(key: String, value: Short): Tag? = put(key, HeapShortTag(value))

    fun putInt(key: String, value: Int): Tag? = put(key, HeapIntTag(value))

    fun putLong(key: String, value: Long): Tag? = put(key, HeapLongTag(value))

    fun putFloat(key: String, value: Float): Tag? = put(key, HeapFloatTag(value))

    fun putDouble(key: String, value: Double): Tag? = put(key, HeapDoubleTag(value))

    fun putString(key: String, value: String): Tag? = put(key, HeapStringTag(value))

    fun putByteArray(key: String, value: ByteArray): Tag? = put(key, HeapByteArrayTag(value))

    fun putIntArray(key: String, value: IntArray): Tag? = put(key, HeapIntArrayTag(value))

    fun putLongArray(key: String, value: LongArray): Tag? = put(key, HeapLongArrayTag(value))

    override fun pin(): CompoundTag

    override fun copy(): CompoundTag

}
