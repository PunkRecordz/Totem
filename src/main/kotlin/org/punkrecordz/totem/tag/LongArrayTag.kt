package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey
import org.punkrecordz.totem.view.LongView

interface LongArrayTag : Tag, PinnableTag<LongArrayTag>, LongView {

    override val key: TagKey get() = TagType.LONG_ARRAY

    override fun pin(): LongArrayTag

    override fun copy(): LongArrayTag

}

