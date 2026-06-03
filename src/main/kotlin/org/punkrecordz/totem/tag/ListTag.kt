package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

interface ListTag<T : Tag> : Tag, PinnableTag<ListTag<T>>, MutableList<T> {

    override val key: TagKey get() = TagType.LIST

    val elementType: TagKey

    override fun pin(): ListTag<T>

    override fun copy(): ListTag<T>

}
