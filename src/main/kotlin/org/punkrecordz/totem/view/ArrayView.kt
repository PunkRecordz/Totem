package org.punkrecordz.totem.view

interface ArrayView {

    val size: Int

    fun isEmpty(): Boolean = size == 0

    fun isNotEmpty(): Boolean = size > 0

}
