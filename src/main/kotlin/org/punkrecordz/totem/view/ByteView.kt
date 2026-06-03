package org.punkrecordz.totem.view

interface ByteView : ArrayView {

    operator fun get(index: Int): Byte

    operator fun set(
        index: Int,
        value: Byte,
    )

    fun copy(): ByteView

}
