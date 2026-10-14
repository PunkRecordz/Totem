package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.view.NativeView
import org.punkrecordz.totem.view.ShortView
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.Objects

@JvmInline
value class NativeShortView(
    override val segment: MemorySegment,
) : ShortView, NativeView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    override operator fun get(index: Int): Short {
        Objects.checkIndex(index, size)

        return segment.get(
            NATIVE_SHORT,
            SHORT_VIEW_HEADER_OFFSET + (index.toLong() shl 1),
        )
    }

    override operator fun set(index: Int, value: Short) {
        Objects.checkIndex(index, size)

        segment.set(
            NATIVE_SHORT,
            SHORT_VIEW_HEADER_OFFSET + (index.toLong() shl 1),
            value,
        )
    }

    override fun replaceAll(
        target: Short,
        replacement: Short,
    ) {
        val elementCount = size.toLong()
        if (elementCount == 0L) return

        val payloadSlice = segment.asSlice(SHORT_VIEW_HEADER_OFFSET)

        NativeShortViewSimdOps.executeParallel(elementCount, payloadSlice) { workerSlice, count ->
            TotemSys.replaceShorts(
                workerSlice,
                count,
                target,
                replacement,
            )
        }
    }

    override fun fill(value: Short) {
        val elementCount = size.toLong()
        if (elementCount == 0L) return

        val payloadSlice = segment.asSlice(SHORT_VIEW_HEADER_OFFSET)

        NativeShortViewSimdOps.executeParallel(elementCount, payloadSlice) { workerSlice, count ->
            TotemSys.fillShorts(
                workerSlice,
                count,
                value,
            )
        }
    }

    override fun translate(lookupTable: ShortArray) {
        val elementCount = size.toLong()
        if (elementCount == 0L || lookupTable.isEmpty()) return

        NativeShortViewSimdOps.translate(
            payloadSlice = segment.asSlice(SHORT_VIEW_HEADER_OFFSET),
            elementCount = elementCount,
            lookupTable = lookupTable,
        )
    }

    override fun translate(lookupTable: IntArray) {
        val shortTable = ShortArray(lookupTable.size) { index -> lookupTable[index].toShort() }
        translate(shortTable)
    }

    override fun scanMatchingIndices(shouldIgnore: BooleanArray): IntArray {
        val elementCount = size.toLong()
        if (elementCount == 0L || shouldIgnore.isEmpty()) return IntArray(0)

        return NativeShortViewSimdOps.scanMatchingIndices(
            payloadSlice = segment.asSlice(SHORT_VIEW_HEADER_OFFSET),
            elementCount = elementCount,
            shouldIgnore = shouldIgnore,
        )
    }

    override fun copy(): NativeShortView {
        val newSegment = Arena.ofAuto().allocateUninitialized(segment.byteSize())
        MemorySegment.copy(segment, 0L, newSegment, 0L, segment.byteSize())

        return NativeShortView(newSegment)
    }

    companion object {

        val NATIVE_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT

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
