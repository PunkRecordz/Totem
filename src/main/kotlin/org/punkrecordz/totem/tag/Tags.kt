package org.punkrecordz.totem.tag

import org.punkrecordz.totem.impl.heap.*
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeIntArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.contract.Tag
import org.punkrecordz.totem.tag.contract.TagKey
import java.lang.foreign.Arena

object Tags {

    fun byte(value: Byte): ByteTag = HeapByteTag(value)

    fun short(value: Short): ShortTag = HeapShortTag(value)

    fun int(value: Int): IntTag = HeapIntTag(value)

    fun long(value: Long): LongTag = HeapLongTag(value)

    fun float(value: Float): FloatTag = HeapFloatTag(value)

    fun double(value: Double): DoubleTag = HeapDoubleTag(value)

    fun string(value: String): StringTag = HeapStringTag(value)

    fun byteArray(value: ByteArray): ByteArrayTag = HeapByteArrayTag(value)

    fun intArray(value: IntArray): IntArrayTag = HeapIntArrayTag(value)

    fun longArray(value: LongArray): LongArrayTag = HeapLongArrayTag(value)

    fun nativeByteArray(
        size: Int,
        arena: Arena,
    ): ByteArrayTag {
        val byteSize = MemoryLayouts.INT.byteSize() + (size.toLong() * MemoryLayouts.BYTE.byteSize())
        val segment = arena.allocateUninitialized(byteSize)
        segment.set(MemoryLayouts.INT, 0L, size)

        return NativeByteArrayTag(segment)
    }

    fun nativeIntArray(
        size: Int,
        arena: Arena,
    ): IntArrayTag {
        val byteSize = MemoryLayouts.INT.byteSize() + (size.toLong() * MemoryLayouts.INT.byteSize())
        val segment = arena.allocateUninitialized(byteSize)
        segment.set(MemoryLayouts.INT, 0L, size)

        return NativeIntArrayTag(segment)
    }

    fun nativeLongArray(
        size: Int,
        arena: Arena,
    ): LongArrayTag {
        val byteSize = MemoryLayouts.INT.byteSize() + (size.toLong() * MemoryLayouts.LONG.byteSize())
        val segment = arena.allocateUninitialized(byteSize)
        segment.set(MemoryLayouts.INT, 0L, size)

        return NativeLongArrayTag(segment)
    }

    fun compound(
        value: MutableMap<String, Tag> = mutableMapOf(),
    ): CompoundTag = HeapCompoundTag(value)

    fun compound(
        builder: CompoundTag.() -> Unit,
    ): CompoundTag {
        return HeapCompoundTag().apply(builder)
    }

    fun <T : Tag> list(
        elementType: TagKey,
        value: MutableList<T> = mutableListOf(),
    ): ListTag<T> = HeapListTag(elementType, value)

    fun <T : Tag> list(
        elementType: TagKey,
        builder: MutableList<T>.() -> Unit,
    ): ListTag<T> {
        val list = mutableListOf<T>()
        list.builder()

        return HeapListTag(elementType, list)
    }

}
