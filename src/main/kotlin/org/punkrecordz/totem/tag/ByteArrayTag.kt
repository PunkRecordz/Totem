package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey
import org.punkrecordz.totem.view.ByteView

interface ByteArrayTag : Tag, PinnableTag<ByteArrayTag>, ByteView {

    override val key: TagKey get() = TagType.BYTE_ARRAY

    override fun pin(): ByteArrayTag

    override fun copy(): ByteArrayTag

}

