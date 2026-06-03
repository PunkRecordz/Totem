package org.punkrecordz.totem.view

import java.util.function.IntConsumer

interface ByteView : ArrayView {

    operator fun get(index: Int): Byte

    operator fun set(
        index: Int,
        value: Byte,
    )

    fun replace(
        target: Byte,
        replacement: Byte,
    )

    fun replaceAll(replacements: Map<Byte, Byte>)

    fun occurrences(): Map<Byte, Int>

    fun forEachIndex(
        target: Byte,
        action: IntConsumer,
    )

}
