package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey
import org.punkrecordz.totem.view.IntView

interface IntArrayTag : Tag, PinnableTag<IntArrayTag>, IntView {

    override val key: TagKey get() = TagType.INT_ARRAY

    override fun pin(): IntArrayTag

    override fun copy(): IntArrayTag

}

