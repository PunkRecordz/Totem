package org.punkrecordz.totem.tag

import org.punkrecordz.totem.tag.contract.PinnableTag
import org.punkrecordz.totem.tag.contract.TagKey

interface ByteTag : NumericTag<Byte>, PinnableTag<ByteTag> {

    override val key: TagKey get() = TagType.BYTE

    override fun pin(): ByteTag

    override fun copy(): ByteTag

}
