package org.punkrecordz.totem.view

import java.util.function.IntConsumer

interface IntView : ArrayView {

    operator fun get(index: Int): Int

    operator fun set(
        index: Int,
        value: Int,
    )

    fun replace(
        target: Int,
        replacement: Int,
    )

    fun replaceAll(replacements: Map<Int, Int>)

    fun occurrences(): Map<Int, Int>

    fun forEachIndex(
        target: Int,
        action: IntConsumer,
    )

}
