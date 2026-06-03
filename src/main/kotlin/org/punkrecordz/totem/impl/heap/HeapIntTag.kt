package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.IntTag

data class HeapIntTag(
    override val value: Int,
) : IntTag {

    override fun pin(): IntTag {
        return this
    }

    override fun copy(): HeapIntTag {
        return this
    }

}
