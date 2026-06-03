package org.punkrecordz.totem.impl.heap

import org.punkrecordz.totem.tag.EndTag

object HeapEndTag : EndTag {

    override fun pin(): EndTag {
        return this
    }

    override fun copy(): HeapEndTag {
        return this
    }

}
