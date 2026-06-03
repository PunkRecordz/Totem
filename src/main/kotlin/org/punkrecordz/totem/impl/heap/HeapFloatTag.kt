package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.FloatTag

data class HeapFloatTag(
    override val value: Float,
) : FloatTag {

    override fun pin(): FloatTag {
        return this
    }

    override fun copy(): HeapFloatTag {
        return this
    }

}
