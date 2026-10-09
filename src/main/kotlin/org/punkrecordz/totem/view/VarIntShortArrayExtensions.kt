package org.punkrecordz.totem.view

import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.Arena

/**
 * Decodes the VarInt compressed byte data inside this [ByteView] into a zero-allocation off-heap [NativeShortView].
 *
 * Strategy:
 * Directly delegates to the native `totem-sys` library with critical Panama downcalls and AVX2 SIMD acceleration.
 * Returning [NativeShortView] directly rather than interface [ShortView] avoids heap boxing of the value class,
 * enabling the Kotlin and HotSpot compilers to erase the abstraction into raw off-heap pointers.
 *
 * Performance targets:
 * Multi-gigabyte per second VarInt decoding throughput with zero JVM heap garbage collection.
 *
 * @param expectedSize The number of Short elements expected in the decoded view.
 * @param arena The FFM Arena to allocate the off-heap MemorySegment for the resulting view.
 */
fun ByteView.toVarIntShortArray(
    expectedSize: Int,
    arena: Arena,
): NativeShortView {
    if (this !is NativeByteArrayTag) {
        throw IllegalArgumentException("Only native off-heap tags can be decoded using native VarInt translation.")
    }

    val view = NativeShortView.of(
        expectedSize,
        arena,
    )

    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val destinationSlice = view.segment.asSlice(MemoryLayouts.INT.byteSize())

    TotemSys.decodeVarIntShorts(
        sourceSlice,
        destinationSlice,
        expectedSize,
    )

    return view
}

/**
 * Encodes this [ShortView] into a VarInt compressed [ByteArrayTag] off-heap.
 *
 * Strategy:
 * Pre-calculates the exact required byte size in a single pass using vectorized AVX2 SIMD in native Rust.
 * This completely eliminates the need for oversized intermediate scratch buffers, secondary reallocation,
 * and massive payload memory copying across the bus.
 *
 * Performance targets:
 * Zero-copy, single-allocation encoding directly into the target [arena].
 *
 * @param arena The FFM Arena to allocate the off-heap MemorySegment for the resulting ByteArrayTag.
 */
fun ShortView.toVarIntByteArray(
    arena: Arena,
): ByteArrayTag {
    if (this !is NativeShortView) {
        throw IllegalArgumentException("Only NativeShortView is supported for native VarInt encoding.")
    }

    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val elementCount = this.size.toLong()

    // calculate exact encoded size via fast SIMD to avoid temporary buffers and memory copying
    val payloadSize = TotemSys.calculateVarIntEncodedSize(sourceSlice, elementCount)
    val totalSize = payloadSize + MemoryLayouts.INT.byteSize()

    val targetSegment = arena.allocateUninitialized(totalSize)
    targetSegment.set(MemoryLayouts.INT, 0L, payloadSize.toInt())

    val destinationSlice = targetSegment.asSlice(MemoryLayouts.INT.byteSize())
    TotemSys.encodeShortsVarInt(
        sourceSlice,
        elementCount,
        destinationSlice,
    )

    return NativeByteArrayTag(targetSegment)
}
