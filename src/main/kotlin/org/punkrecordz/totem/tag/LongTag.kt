package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey

interface LongTag : NumericTag<Long>, PinnableTag<LongTag> {

    override val key: TagKey get() = TagType.LONG

    override fun pin(): LongTag

    override fun copy(): LongTag

}
