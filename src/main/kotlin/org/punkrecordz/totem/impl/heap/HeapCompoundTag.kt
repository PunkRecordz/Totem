package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.contract.Tag

data class HeapCompoundTag(
    val value: MutableMap<String, Tag> = mutableMapOf(),
) : CompoundTag, MutableMap<String, Tag> by value {

    override fun pin(): CompoundTag {
        val newMap = mutableMapOf<String, Tag>()

        for ((key, tag) in value) {
            newMap[key] = tag.pin()
        }

        return copy(value = newMap)
    }

    override fun copy(): HeapCompoundTag {
        val newMap = mutableMapOf<String, Tag>()

        for ((key, tag) in value) {
            newMap[key] = tag.copy()
        }

        return copy(value = newMap)
    }

}
