package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.view.NativeView
import org.punkrecordz.totem.view.ShortView
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

@JvmInline
value class NativeShortView(
    override val segment: MemorySegment,
) : ShortView, NativeView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    override operator fun get(index: Int): Short {
        return segment.get(
            NATIVE_SHORT,
            MemoryLayouts.INT.byteSize() + (index.toLong() * Short.SIZE_BYTES),
        )
    }

    override operator fun set(index: Int, value: Short) {
        segment.set(
            NATIVE_SHORT,
            MemoryLayouts.INT.byteSize() + (index.toLong() * Short.SIZE_BYTES),
            value,
        )
    }

    override fun copy(): NativeShortView {
        val newSegment = Arena.ofAuto().allocateUninitialized(segment.byteSize())
        MemorySegment.copy(segment, 0L, newSegment, 0L, segment.byteSize())

        return NativeShortView(newSegment)
    }

    companion object {
        val NATIVE_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.nativeOrder())

        fun of(array: ShortArray, arena: Arena): NativeShortView {
            val byteSize = MemoryLayouts.INT.byteSize() + (array.size.toLong() * MemoryLayouts.SHORT.byteSize())
            val segment = arena.allocateUninitialized(byteSize)
            segment.set(MemoryLayouts.INT, 0L, array.size)

            val destinationOffset = MemoryLayouts.INT.byteSize()
            MemorySegment.copy(
                MemorySegment.ofArray(array),
                0L,
                segment,
                destinationOffset,
                array.size.toLong() * Short.SIZE_BYTES,
            )

            return NativeShortView(segment)
        }

        fun of(size: Int, arena: Arena): NativeShortView {
            val byteSize = MemoryLayouts.INT.byteSize() + (size.toLong() * MemoryLayouts.SHORT.byteSize())
            val segment = arena.allocateUninitialized(byteSize)
            segment.set(MemoryLayouts.INT, 0L, size)

            return NativeShortView(segment)
        }
    }

}
