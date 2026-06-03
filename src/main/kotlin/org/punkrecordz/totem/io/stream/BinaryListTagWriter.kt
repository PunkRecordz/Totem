package org.punkrecordz.totem.io.stream

import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

interface BinaryListTagWriter {

    fun byte(value: Byte)

    fun short(value: Short)

    fun int(value: Int)

    fun long(value: Long)

    fun float(value: Float)

    fun double(value: Double)

    fun byteArray(value: ByteArray)

    fun string(value: String)

    fun intArray(value: IntArray)

    fun longArray(value: LongArray)

    fun compound(block: BinaryTagWriter.() -> Unit)

    fun list(
        elementType: TagKey,
        size: Int,
        block: BinaryListTagWriter.() -> Unit,
    )

    fun tag(tag: Tag)

}

