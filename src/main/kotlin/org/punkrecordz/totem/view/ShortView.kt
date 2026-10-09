package org.punkrecordz.totem.view

interface ShortView : ArrayView {

    operator fun get(index: Int): Short

    operator fun set(
        index: Int,
        value: Short,
    )

    fun copy(): ShortView

    fun replaceAll(
        target: Short,
        replacement: Short,
    ) {
        for (index in 0 until size) {
            if (get(index) == target) {
                set(index, replacement)
            }
        }
    }

    fun fill(value: Short) {
        for (index in 0 until size) {
            set(index, value)
        }
    }

}
