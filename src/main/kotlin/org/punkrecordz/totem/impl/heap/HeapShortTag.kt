package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.ShortTag

data class HeapShortTag(
    override val value: Short,
) : ShortTag {

    override fun pin(): ShortTag {
        return this
    }

    override fun copy(): HeapShortTag {
        return this
    }

}
