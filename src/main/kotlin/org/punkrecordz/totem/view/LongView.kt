package org.punkrecordz.totem.view

import java.util.function.IntConsumer

interface LongView : ArrayView {

    operator fun get(index: Int): Long

    operator fun set(
        index: Int,
        value: Long,
    )

    fun replace(
        target: Long,
        replacement: Long,
    )

    fun replaceAll(replacements: Map<Long, Long>)

    fun occurrences(): Map<Long, Int>

    fun forEachIndex(
        target: Long,
        action: IntConsumer,
    )

}
