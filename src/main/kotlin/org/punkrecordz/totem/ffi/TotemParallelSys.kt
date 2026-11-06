package org.punkrecordz.totem.ffi

import java.lang.foreign.MemorySegment

internal object TotemParallelSys {

    fun decodeVarIntShortsParallel(
        sourceSlice: MemorySegment,
        destinationSlice: MemorySegment,
        expectedSize: Int,
    ): Long {
        val status = TotemParallelBindings.decodeVarIntShortsParallel.invokeExact(
            sourceSlice,
            sourceSlice.byteSize(),
            destinationSlice,
            expectedSize,
        ) as Long

        return VarIntDecodeStatus.verify(status, expectedSize)
    }

    fun calculateVarIntPartitionSizes(
        sourceSlice: MemorySegment,
        elementCount: Long,
        partitionSizesOut: MemorySegment,
        maxPartitions: Int,
        partitionCountOut: MemorySegment,
        chunkSizeOut: MemorySegment,
    ): Long {
        val totalBytes = TotemParallelBindings.varIntPartitionSizes.invokeExact(
            sourceSlice,
            elementCount,
            partitionSizesOut,
            maxPartitions,
            partitionCountOut,
            chunkSizeOut,
        ) as Long

        if (totalBytes < 0L) {
            throw IllegalStateException("Native VarInt partition sizing failed with status $totalBytes")
        }

        return totalBytes
    }

    fun encodeShortsVarIntParallel(
        sourceSlice: MemorySegment,
        elementCount: Long,
        partitionSizes: MemorySegment,
        partitionCount: Int,
        chunkSize: Long,
        destinationSlice: MemorySegment,
    ): Long {
        val writtenBytes = TotemParallelBindings.encodeShortsVarIntParallel.invokeExact(
            sourceSlice,
            elementCount,
            partitionSizes,
            partitionCount,
            chunkSize,
            destinationSlice,
            destinationSlice.byteSize(),
        ) as Long

        if (writtenBytes < 0L) {
            throw IllegalStateException("Native parallel VarInt encoding failed with status $writtenBytes")
        }

        return writtenBytes
    }

    fun replaceShortsParallel(
        dataSlice: MemorySegment,
        length: Long,
        target: Short,
        replacement: Short,
    ) {
        TotemParallelBindings.replaceShortsParallel.invokeExact(dataSlice, length, target, replacement)
    }

    fun fillShortsParallel(
        dataSlice: MemorySegment,
        length: Long,
        value: Short,
    ) {
        TotemParallelBindings.fillShortsParallel.invokeExact(dataSlice, length, value)
    }

    fun translateShortsParallel(
        dataSlice: MemorySegment,
        length: Long,
        lookupTableSlice: MemorySegment,
    ) {
        TotemParallelBindings.translateShortsLutParallel.invokeExact(dataSlice, length, lookupTableSlice)
    }

}
