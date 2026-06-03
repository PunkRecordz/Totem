package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.LongTag

data class HeapLongTag(
    override val value: Long,
) : LongTag {

    override fun pin(): LongTag {
        return this
    }

    override fun copy(): HeapLongTag {
        return this
    }

}
