package org.punkrecordz.totem.view

interface IntView : ArrayView {

    operator fun get(index: Int): Int

    operator fun set(
        index: Int,
        value: Int,
    )

    fun copy(): IntView

}
