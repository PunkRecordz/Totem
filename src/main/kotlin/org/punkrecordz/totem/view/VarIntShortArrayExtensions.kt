package org.punkrecordz.totem.view

import org.punkrecordz.totem.ffi.TotemParallelSys
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.ByteArrayTag
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

private const val PARALLEL_THRESHOLD_ELEMENTS: Long = 65536L

fun ByteView.toVarIntShortArray(
    expectedSize: Int,
    arena: Arena,
): NativeShortView {
    if (this !is NativeByteArrayTag) {
        throw IllegalArgumentException("Only native off-heap tags can be decoded using native VarInt translation.")
    }

    val view = NativeShortView.of(expectedSize, arena)
    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val destinationSlice = view.segment.asSlice(MemoryLayouts.INT.byteSize())

    if (expectedSize < PARALLEL_THRESHOLD_ELEMENTS || sourceSlice.byteSize() < PARALLEL_THRESHOLD_ELEMENTS) {
        TotemSys.decodeVarIntShorts(
            sourceSlice,
            destinationSlice,
            expectedSize,
        )

        return view
    }

    TotemParallelSys.decodeVarIntShortsParallel(
        sourceSlice,
        destinationSlice,
        expectedSize,
    )

    return view
}

fun ShortView.toVarIntByteArray(
    arena: Arena,
): ByteArrayTag {
    if (this !is NativeShortView) {
        throw IllegalArgumentException("Only NativeShortView is supported for native VarInt encoding.")
    }

    val sourceSlice = this.segment.asSlice(MemoryLayouts.INT.byteSize())
    val elementCount = this.size.toLong()

    if (elementCount < PARALLEL_THRESHOLD_ELEMENTS) {
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

    val scratch = VarIntEncodeScratch.get()
    val totalPayloadSize = TotemParallelSys.calculateVarIntPartitionSizes(
        sourceSlice = sourceSlice,
        elementCount = elementCount,
        partitionSizesOut = scratch.partitionSizes,
        maxPartitions = MAX_VARINT_PARTITIONS,
        partitionCountOut = scratch.partitionCountOut,
        chunkSizeOut = scratch.chunkSizeOut,
    )

    val partitionCount = scratch.partitionCountOut.get(ValueLayout.JAVA_INT_UNALIGNED, 0L)
    val chunkSize = scratch.chunkSizeOut.get(ValueLayout.JAVA_LONG_UNALIGNED, 0L)
    val totalSize = totalPayloadSize + MemoryLayouts.INT.byteSize()

    val targetSegment = arena.allocateUninitialized(totalSize)
    targetSegment.set(MemoryLayouts.INT, 0L, totalPayloadSize.toInt())

    val destinationSlice = targetSegment.asSlice(MemoryLayouts.INT.byteSize())
    TotemParallelSys.encodeShortsVarIntParallel(
        sourceSlice = sourceSlice,
        elementCount = elementCount,
        partitionSizes = scratch.partitionSizes,
        partitionCount = partitionCount,
        chunkSize = chunkSize,
        destinationSlice = destinationSlice,
    )

    return NativeByteArrayTag(targetSegment)
}
