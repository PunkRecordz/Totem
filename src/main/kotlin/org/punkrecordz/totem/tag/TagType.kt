package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.TagKey

object TagType {

    val END: TagKey = DefaultTagKey(0, "End")

    val BYTE: TagKey = DefaultTagKey(1, "Byte")

    val SHORT: TagKey = DefaultTagKey(2, "Short")

    val INT: TagKey = DefaultTagKey(3, "Int")

    val LONG: TagKey = DefaultTagKey(4, "Long")

    val FLOAT: TagKey = DefaultTagKey(5, "Float")

    val DOUBLE: TagKey = DefaultTagKey(6, "Double")

    val BYTE_ARRAY: TagKey = DefaultTagKey(7, "ByteArray")

    val STRING: TagKey = DefaultTagKey(8, "String")

    val LIST: TagKey = DefaultTagKey(9, "List")

    val COMPOUND: TagKey = DefaultTagKey(10, "Compound")

    val INT_ARRAY: TagKey = DefaultTagKey(11, "IntArray")

    val LONG_ARRAY: TagKey = DefaultTagKey(12, "LongArray")

    fun fromId(id: Int): TagKey {
        return when (id) {
            0 -> END
            1 -> BYTE
            2 -> SHORT
            3 -> INT
            4 -> LONG
            5 -> FLOAT
            6 -> DOUBLE
            7 -> BYTE_ARRAY
            8 -> STRING
            9 -> LIST
            10 -> COMPOUND
            11 -> INT_ARRAY
            12 -> LONG_ARRAY
            else -> throw IllegalArgumentException("Unknown NBT tag ID: $id")
        }
    }

}

private data class DefaultTagKey(
    override val id: Int,
    override val name: String,
) : TagKey