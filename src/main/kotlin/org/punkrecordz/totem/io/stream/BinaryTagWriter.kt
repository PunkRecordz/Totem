package org.punkrecordz.totem.io.stream

import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

interface BinaryTagWriter {

    fun byte(name: String, value: Byte)

    fun short(name: String, value: Short)

    fun int(name: String, value: Int)

    fun long(name: String, value: Long)

    fun float(name: String, value: Float)

    fun double(name: String, value: Double)

    fun byteArray(name: String, value: ByteArray)

    fun string(name: String, value: String)

    fun intArray(name: String, value: IntArray)

    fun longArray(name: String, value: LongArray)

    fun compound(name: String, block: BinaryTagWriter.() -> Unit)

    fun list(
        name: String,
        elementType: TagKey,
        size: Int,
        block: BinaryListTagWriter.() -> Unit,
    )

    fun tag(name: String, tag: Tag)

}

