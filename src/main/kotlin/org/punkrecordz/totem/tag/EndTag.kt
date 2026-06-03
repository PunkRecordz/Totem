package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey
import org.punkrecordz.totem.tag.contract.TagValue

interface EndTag : TagValue<Unit>, PinnableTag<EndTag> {

    override val key: TagKey get() = TagType.END

    override val value: Unit get() = Unit

    override fun pin(): EndTag

    override fun copy(): EndTag

}
