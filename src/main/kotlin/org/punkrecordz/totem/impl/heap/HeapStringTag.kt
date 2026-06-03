package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.StringTag

data class HeapStringTag(
    override val value: String,
) : StringTag {

    override fun pin(): StringTag {
        return this
    }

    override fun copy(): HeapStringTag {
        return this
    }

}
