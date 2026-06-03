package org.punkrecordz.totem.view

import java.util.function.IntConsumer

interface ShortView : ArrayView {

    operator fun get(index: Int): Short

    operator fun set(
        index: Int,
        value: Short,
    )

    fun replace(
        target: Short,
        replacement: Short,
    )

    fun replaceAll(replacements: Map<Short, Short>)

    fun occurrences(): Map<Short, Int>

    fun indexOf(target: Short): Int

    fun firstIndices(): Map<Short, Int>

    fun forEachIndex(
        target: Short,
        action: IntConsumer,
    )

    fun pin(): ShortArray

    fun copy(): ShortView

}

