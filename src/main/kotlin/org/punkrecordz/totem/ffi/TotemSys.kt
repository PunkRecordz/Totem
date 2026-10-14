package org.punkrecordz.totem.ffi

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

object TotemSys {

    fun scanGzipMembers(
        compressedData: MemorySegment,
        outputOffsets: MemorySegment,
        maxMembers: Int,
    ): Int = TotemSysBindings.scanGzipMembers.invokeExact(
        compressedData,
        compressedData.byteSize(),
        outputOffsets,
        maxMembers,
    ) as Int

    fun scanGzipMemberOffsets(
        compressedData: MemorySegment,
        maxMembers: Int = 2048,
    ): LongArray = Arena.ofConfined().use { tempArena ->
        val offsetsBuffer = tempArena.allocate(ValueLayout.JAVA_LONG, maxMembers.toLong())
        val memberCount = scanGzipMembers(compressedData, offsetsBuffer, maxMembers)

        if (memberCount <= 0) {
            return@use LongArray(0)
        }

        LongArray(memberCount).also { offsets ->
            MemorySegment.copy(offsetsBuffer, ValueLayout.JAVA_LONG, 0L, offsets, 0, memberCount)
        }
    }

    fun decodeVarIntShorts(
        sourceSlice: MemorySegment,
        destinationSlice: MemorySegment,
        expectedSize: Int,
    ): Long {
        val result = TotemSysBindings.decodeVarIntShorts.invokeExact(
            sourceSlice,
            sourceSlice.byteSize(),
            destinationSlice,
            expectedSize,
        ) as Long

        if (result < 0L) {
            when (result) {
                -1L -> throw IllegalArgumentException(
                    "Malformed VarInt data: byte buffer was truncated in the middle of a VarInt sequence.",
                )
                -2L -> throw IllegalArgumentException(
                    "Incomplete VarInt data: expected $expectedSize elements, but buffer contained fewer VarInt values.",
                )
                else -> throw IllegalArgumentException(
                    "Failed to decode VarInt data: native decoder returned error code $result.",
                )
            }
        }

        return result
    }

    fun encodeShortsVarInt(
        sourceSlice: MemorySegment,
        sourceLength: Long,
        destinationSlice: MemorySegment,
    ): Long = TotemSysBindings.encodeShortsVarInt.invokeExact(
        sourceSlice,
        sourceLength,
        destinationSlice,
        destinationSlice.byteSize(),
    ) as Long

    fun calculateVarIntEncodedSize(
        sourceSlice: MemorySegment,
        sourceLength: Long,
    ): Long = TotemSysBindings.varIntEncodedSize.invokeExact(
        sourceSlice,
        sourceLength,
    ) as Long

    fun countVarIntTerminals(
        sourceSlice: MemorySegment,
        sourceLength: Long = sourceSlice.byteSize(),
    ): Long = TotemSysBindings.countVarIntTerminals.invokeExact(
        sourceSlice,
        sourceLength,
    ) as Long

    fun replaceShorts(
        dataSlice: MemorySegment,
        length: Long,
        target: Short,
        replacement: Short,
    ) {
        TotemSysBindings.replaceShorts.invokeExact(dataSlice, length, target, replacement)
    }

    fun fillShorts(
        dataSlice: MemorySegment,
        length: Long,
        value: Short,
    ) {
        TotemSysBindings.fillShorts.invokeExact(dataSlice, length, value)
    }

    fun translateShorts(
        dataSlice: MemorySegment,
        length: Long,
        lookupTableSlice: MemorySegment,
    ) {
        TotemSysBindings.translateShortsLut.invokeExact(dataSlice, length, lookupTableSlice)
    }

    fun countMatchingShorts(
        sourceSlice: MemorySegment,
        elementCount: Long,
        ignoreLookupTable: MemorySegment,
    ): Long = TotemSysBindings.countMatchingShorts.invokeExact(
        sourceSlice,
        elementCount,
        ignoreLookupTable,
    ) as Long

    fun scanMatchingShorts(
        sourceSlice: MemorySegment,
        elementCount: Long,
        baseIndex: Long,
        ignoreLookupTable: MemorySegment,
        outputIndices: MemorySegment,
        maxMatches: Long,
    ): Long = TotemSysBindings.scanMatchingShorts.invokeExact(
        sourceSlice,
        elementCount,
        baseIndex,
        ignoreLookupTable,
        outputIndices,
        maxMatches,
    ) as Long

    fun calculateCompoundSize(
        segment: MemorySegment,
        offset: Long = 0L,
    ): Long {
        val slice = if (offset == 0L) segment else segment.asSlice(offset)
        val calculatedSize = TotemSysBindings.calculateCompoundSize.invokeExact(slice, slice.byteSize()) as Long

        if (calculatedSize < 0L) {
            when (calculatedSize) {
                -3L -> throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of 512 at offset $offset")
                -2L -> throw IllegalArgumentException("Malformed NBT compound data at offset $offset")
                else -> throw IndexOutOfBoundsException("Truncated NBT compound data at offset $offset")
            }
        }

        return calculatedSize
    }

    fun findCompoundEntry(
        segment: MemorySegment,
        offset: Long,
        keySegment: MemorySegment,
        keyLength: Short,
    ): Long {
        val slice = if (offset == 0L) segment else segment.asSlice(offset)
        val result = TotemSysBindings.findCompoundEntry.invokeExact(
            slice,
            slice.byteSize(),
            keySegment,
            keyLength,
        ) as Long

        if (result < -1L) {
            when (result) {
                -3L -> throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of 512 at offset $offset")
                else -> throw IllegalArgumentException("Malformed NBT compound entry encountered at offset $offset")
            }
        }

        return result
    }

    fun findVarIntBoundaries(
        sourceSlice: MemorySegment,
        partitionCount: Int,
        outputByteStarts: MemorySegment,
    ) {
        TotemSysBindings.findVarIntBoundaries.invokeExact(
            sourceSlice,
            sourceSlice.byteSize(),
            partitionCount,
            outputByteStarts,
        )
    }

    fun calculateListSize(
        segment: MemorySegment,
        offset: Long = 0L,
    ): Long {
        val slice = if (offset == 0L) segment else segment.asSlice(offset)
        val calculatedSize = TotemSysBindings.calculateListSize.invokeExact(slice, slice.byteSize()) as Long

        if (calculatedSize < 0L) {
            when (calculatedSize) {
                -3L -> throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of 512 at offset $offset")
                -2L -> throw IllegalArgumentException("Malformed NBT list data at offset $offset")
                else -> throw IndexOutOfBoundsException("Truncated NBT list data at offset $offset")
            }
        }

        return calculatedSize
    }

}
