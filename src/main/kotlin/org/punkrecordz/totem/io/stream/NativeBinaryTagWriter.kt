package org.punkrecordz.totem.io.stream

import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeIntArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey

class NativeBinaryTagWriter(
    val outputStream: NativeByteArrayOutputStream,
) : BinaryTagWriter {

    private fun writeHeader(type: TagKey, name: String?) {
        outputStream.writeByte(type.id.toByte())

        if (name != null) {
            outputStream.writeString(name)
        } else {
            outputStream.writeShort(0)
        }
    }

    private fun writeListHeader(elementType: TagKey, size: Int) {
        outputStream.writeByte(elementType.id.toByte())
        outputStream.writeInt(size)
    }

    override fun byte(name: String, value: Byte) {
        writeHeader(TagType.BYTE, name)
        outputStream.writeByte(value)
    }

    override fun short(name: String, value: Short) {
        writeHeader(TagType.SHORT, name)
        outputStream.writeShort(value)
    }

    override fun int(name: String, value: Int) {
        writeHeader(TagType.INT, name)
        outputStream.writeInt(value)
    }

    override fun long(name: String, value: Long) {
        writeHeader(TagType.LONG, name)
        outputStream.writeLong(value)
    }

    override fun float(name: String, value: Float) {
        writeHeader(TagType.FLOAT, name)
        outputStream.writeFloat(value)
    }

    override fun double(name: String, value: Double) {
        writeHeader(TagType.DOUBLE, name)
        outputStream.writeDouble(value)
    }

    override fun byteArray(name: String, value: ByteArray) {
        writeHeader(TagType.BYTE_ARRAY, name)
        outputStream.writeInt(value.size)

        outputStream.writeByteArray(value)
    }

    override fun string(name: String, value: String) {
        writeHeader(TagType.STRING, name)
        outputStream.writeString(value)
    }

    override fun intArray(name: String, value: IntArray) {
        writeHeader(TagType.INT_ARRAY, name)
        outputStream.writeInt(value.size)

        outputStream.writeIntArray(value)
    }

    override fun longArray(name: String, value: LongArray) {
        writeHeader(TagType.LONG_ARRAY, name)
        outputStream.writeInt(value.size)

        outputStream.writeLongArray(value)
    }

    override fun compound(name: String, block: BinaryTagWriter.() -> Unit) {
        writeHeader(TagType.COMPOUND, name)
        this.block()

        outputStream.writeByte(TagType.END.id.toByte())
    }

    override fun list(
        name: String,
        elementType: TagKey,
        size: Int,
        block: BinaryListTagWriter.() -> Unit,
    ) {
        writeHeader(TagType.LIST, name)
        writeListHeader(elementType, size)

        val listWriter = NativeBinaryListTagWriter(this, elementType, size)
        listWriter.block()
        listWriter.verifyComplete()
    }

    override fun tag(name: String, tag: Tag) {
        writeHeader(tag.key, name)

        val protocol = ProtocolRegistry.getOrThrow(tag.key.id)
        val size = protocol.sizeOf(tag)

        val nativeSegment = when (tag) {
            is NativeByteArrayTag -> tag.segment
            is NativeIntArrayTag -> tag.segment
            is NativeLongArrayTag -> tag.segment
            else -> null
        }

        if (nativeSegment != null) {
            outputStream.writeSegment(nativeSegment)

            return
        }

        val tempSegment = outputStream.arena.allocateUninitialized(size)

        protocol.write(tempSegment, 0L, tag)
        outputStream.writeSegment(tempSegment, size)
    }

}
