package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey
import org.punkrecordz.totem.tag.contract.TagValue

interface StringTag : TagValue<String>, PinnableTag<StringTag> {

    override val key: TagKey get() = TagType.STRING

    override fun pin(): StringTag

    override fun copy(): StringTag

}
