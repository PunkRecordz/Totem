package org.punkrecordz.totem.view

import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Decodes the VarInt compressed byte data inside this [ByteView] into a zero-allocation off-heap [ShortView].
 *
 * To avoid any JVM heap allocations and maintain optimal performance, the decoding loop is
 * directly delegated to the native totem-sys Rust library.
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
 * Encodes this [ShortView] into a VarInt compressed [ByteArrayTag] off-heap.
 *
 * Since the size of the VarInt compressed output depends on the actual block IDs, it
 * cannot be known before encoding. To prevent off-heap memory leaks or wastage in
 * long-lived arenas, we encode the data in a temporary confined arena of maximum possible
 * size. Once encoding is done, we copy only the exact payload to the destination [arena]
 * and immediately free the temporary workspace.
 *
 * This ensures single-pass encoding speed with zero long-term memory overhead.
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

    return Arena.ofConfined().use { tempArena ->
        val tempSegment = tempArena.allocateUninitialized(maxPossibleSize)
        val sourceSlice = this.segment.asSlice(4L)
        val destinationSlice = tempSegment.asSlice(4L)

        val payloadSize = TotemSys.encodeShortsVarInt(
            sourceSlice,
            this.size.toLong(),
            destinationSlice,
        )

        tempSegment.set(MemoryLayouts.INT, 0L, payloadSize.toInt())

        val exactSize = payloadSize + MemoryLayouts.INT.byteSize()
        val finalSegment = arena.allocateUninitialized(exactSize)

        MemorySegment.copy(tempSegment, 0L, finalSegment, 0L, exactSize)

        NativeByteArrayTag(finalSegment)
    }
}
