package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey

interface ShortTag : NumericTag<Short>, PinnableTag<ShortTag> {

    override val key: TagKey get() = TagType.SHORT

    override fun pin(): ShortTag

    override fun copy(): ShortTag

}
