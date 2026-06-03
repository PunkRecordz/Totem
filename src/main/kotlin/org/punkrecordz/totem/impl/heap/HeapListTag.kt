package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.ListTag
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey


data class HeapListTag<T : Tag>(
    override val elementType: TagKey,
    val value: MutableList<T> = mutableListOf(),
) : ListTag<T>, MutableList<T> by value {

    @Suppress("UNCHECKED_CAST")
    override fun pin(): ListTag<T> {
        val newList = mutableListOf<T>()

        for (element in value) {
            newList.add(element.pin() as T)
        }

        return copy(value = newList)
    }

    @Suppress("UNCHECKED_CAST")
    override fun copy(): HeapListTag<T> {
        val newList = mutableListOf<T>()

        for (element in value) {
            newList.add(element.copy() as T)
        }

        return copy(value = newList)
    }

}
