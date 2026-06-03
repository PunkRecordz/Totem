package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey

interface DoubleTag : NumericTag<Double>, PinnableTag<DoubleTag> {

    override val key: TagKey get() = TagType.DOUBLE

    override fun pin(): DoubleTag

    override fun copy(): DoubleTag

}
