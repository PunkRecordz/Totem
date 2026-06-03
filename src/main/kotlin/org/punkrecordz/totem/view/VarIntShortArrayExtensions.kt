package org.punkrecordz.totem.view

import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.Arena

/**
 * Strategy: Decodes the VarInt compressed byte data inside this [ByteView] into a zero-allocation off-heap [ShortView].
 *
 * Performance targets:
 * - Directly delegate decoding loop to the native totem-sys Rust library.
 * - Zero JVM heap allocations.
 *
 * @param expectedSize The number of Short elements expected in the decoded view.
 * @param arena The FFM Arena to allocate the off-heap MemorySegment for the resulting view.
 */
fun ByteView.toVarIntShortArray(
    expectedSize: Int,
    arena: Arena,
): ShortView {
    if (this !is NativeByteArrayTag) {
        throw IllegalArgumentException("Only native off-heap tags can be decoded using native VarInt translation.")
    }

    val view = NativeShortView.of(
        expectedSize,
        arena,
    )

    val sourceSlice = this.segment.asSlice(4L)
    val destinationSlice = view.segment.asSlice(4L)

    TotemSys.decodeVarIntShorts(
        sourceSlice,
        destinationSlice,
        expectedSize,
    )

    return view
}

/**
 * Strategy: Encodes this [ShortView] into a VarInt compressed [ByteArrayTag] off-heap.
 *
 * Performance targets:
 * - Single-pass encoding directly to off-heap segment.
 * - No intermediate array copy or JVM heap allocation.
 *
 * @param arena The FFM Arena to allocate the off-heap MemorySegment for the resulting ByteArrayTag.
 */
fun ShortView.toVarIntByteArray(
    arena: Arena,
): ByteArrayTag {
    if (this !is NativeShortView) {
        throw IllegalArgumentException("Only NativeShortView is supported for native VarInt encoding.")
    }

    val maxPossibleSize = size.toLong() * 3L + MemoryLayouts.INT.byteSize() + 8L
    val memorySegment = arena.allocateUninitialized(maxPossibleSize)

    val sourceSlice = this.segment.asSlice(4L)
    val destinationSlice = memorySegment.asSlice(4L)

    // Invoke native encoder through totem-sys FFI bridge
    val payloadSize = TotemSys.encodeShortsVarInt(
        sourceSlice,
        this.size.toLong(),
        destinationSlice,
    )

    // Write final size to the header of the destination segment
    memorySegment.set(MemoryLayouts.INT, 0L, payloadSize.toInt())

    return NativeByteArrayTag(
        memorySegment.asSlice(
            0,
            payloadSize + MemoryLayouts.INT.byteSize(),
        ),
    )
}
