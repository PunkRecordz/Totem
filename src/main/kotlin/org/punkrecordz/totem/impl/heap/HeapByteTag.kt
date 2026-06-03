package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.ByteTag

data class HeapByteTag(
    override val value: Byte,
) : ByteTag {

    override fun pin(): ByteTag {
        return this
    }

    override fun copy(): HeapByteTag {
        return this
    }

}
