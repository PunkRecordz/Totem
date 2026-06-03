package org.punkrecordz.totem.view

interface LongView : ArrayView {

    operator fun get(index: Int): Long

    operator fun set(
        index: Int,
        value: Long,
    )

    fun copy(): LongView

}
