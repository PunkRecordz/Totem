package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey

interface IntTag : NumericTag<Int>, PinnableTag<IntTag> {

    override val key: TagKey get() = TagType.INT

    override fun pin(): IntTag

    override fun copy(): IntTag

}
