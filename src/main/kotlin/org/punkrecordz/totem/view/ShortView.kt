package org.punkrecordz.totem.view

interface ShortView : ArrayView {

    operator fun get(index: Int): Short

    operator fun set(
        index: Int,
        value: Short,
    )

    fun copy(): ShortView

}
