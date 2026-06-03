package org.punkrecordz.totem.io.stream

import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeIntArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

class NativeBinaryListTagWriter(
    private val parent: NativeBinaryTagWriter,
    private val elementType: TagKey,
    private val expectedSize: Int,
) : BinaryListTagWriter {

    private var writtenCount = 0

    private fun verifyType(type: TagKey) {
        require(elementType == type) {
            "Cannot write $type element in a list of $elementType"
        }
    }

    private fun verifyElementWrite() {
        writtenCount++

        if (writtenCount > expectedSize) {
            throw IllegalStateException(
                "Attempted to write more elements ($writtenCount) than the declared size ($expectedSize) of the list of $elementType"
            )
        }
    }

    fun verifyComplete() {
        if (writtenCount != expectedSize) {
            throw IllegalStateException(
                "Declared list size was $expectedSize, but only $writtenCount elements were actually written for the list of $elementType"
            )
        }
    }

    override fun byte(value: Byte) {
        verifyType(TagType.BYTE)
        verifyElementWrite()

        parent.outputStream.writeByte(value)
    }

    override fun short(value: Short) {
        verifyType(TagType.SHORT)
        verifyElementWrite()

        parent.outputStream.writeShort(value)
    }

    override fun int(value: Int) {
        verifyType(TagType.INT)
        verifyElementWrite()

        parent.outputStream.writeInt(value)
    }

    override fun long(value: Long) {
        verifyType(TagType.LONG)
        verifyElementWrite()

        parent.outputStream.writeLong(value)
    }

    override fun float(value: Float) {
        verifyType(TagType.FLOAT)
        verifyElementWrite()

        parent.outputStream.writeFloat(value)
    }

    override fun double(value: Double) {
        verifyType(TagType.DOUBLE)
        verifyElementWrite()

        parent.outputStream.writeDouble(value)
    }

    override fun byteArray(value: ByteArray) {
        verifyType(TagType.BYTE_ARRAY)
        verifyElementWrite()

        parent.outputStream.writeInt(value.size)
        parent.outputStream.writeByteArray(value)
    }

    override fun string(value: String) {
        verifyType(TagType.STRING)
        verifyElementWrite()

        parent.outputStream.writeString(value)
    }

    override fun intArray(value: IntArray) {
        verifyType(TagType.INT_ARRAY)
        verifyElementWrite()

        parent.outputStream.writeInt(value.size)
        parent.outputStream.writeIntArray(value)
    }

    override fun longArray(value: LongArray) {
        verifyType(TagType.LONG_ARRAY)
        verifyElementWrite()

        parent.outputStream.writeInt(value.size)
        parent.outputStream.writeLongArray(value)
    }

    override fun compound(block: BinaryTagWriter.() -> Unit) {
        verifyType(TagType.COMPOUND)
        verifyElementWrite()

        parent.block()
        parent.outputStream.writeByte(TagType.END.id.toByte())
    }

    override fun list(
        elementType: TagKey,
        size: Int,
        block: BinaryListTagWriter.() -> Unit,
    ) {
        verifyType(TagType.LIST)
        verifyElementWrite()

        parent.outputStream.writeByte(elementType.id.toByte())
        parent.outputStream.writeInt(size)

        val listWriter = NativeBinaryListTagWriter(parent, elementType, size)
        listWriter.block()
        listWriter.verifyComplete()
    }

    override fun tag(tag: Tag) {
        verifyType(tag.key)
        verifyElementWrite()

        val segmentAndSize = when (tag) {
            is NativeByteArrayTag -> tag.segment to tag.sizeInBytes
            is NativeIntArrayTag -> tag.segment to tag.sizeInBytes
            is NativeLongArrayTag -> tag.segment to tag.sizeInBytes
            else -> null
        }

        if (segmentAndSize != null) {
            parent.outputStream.writeSegment(segmentAndSize.first, segmentAndSize.second)

            return
        }

        val protocol = ProtocolRegistry.getOrThrow(tag.key.id)
        val size = protocol.sizeOf(tag)

        val tempSegment = parent.outputStream.arena.allocateUninitialized(size)

        protocol.write(tempSegment, 0L, tag)
        parent.outputStream.writeSegment(tempSegment, size)
    }

}
