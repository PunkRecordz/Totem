package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey

interface FloatTag : NumericTag<Float>, PinnableTag<FloatTag> {

    override val key: TagKey get() = TagType.FLOAT

    override fun pin(): FloatTag

    override fun copy(): FloatTag

}
