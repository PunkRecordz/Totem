package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.DoubleTag

data class HeapDoubleTag(
    override val value: Double,
) : DoubleTag {

    override fun pin(): DoubleTag {
        return this
    }

    override fun copy(): HeapDoubleTag {
        return this
    }

}
