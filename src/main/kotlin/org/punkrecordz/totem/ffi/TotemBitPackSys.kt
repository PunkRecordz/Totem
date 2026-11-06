package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

object TotemBitPackSys {

    private val bitPackDescriptor: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_BOOLEAN,
        ValueLayout.JAVA_BOOLEAN,
    )

    private val unpackLongsToShortsHandle: MethodHandle by lazy {
        NativeDowncalls.bindCriticalHeap("unpack_longs_to_shorts", bitPackDescriptor)
    }

    private val packShortsToLongsHandle: MethodHandle by lazy {
        NativeDowncalls.bindCriticalHeap("pack_shorts_to_longs", bitPackDescriptor)
    }

    private val unpackLongsToLongsHandle: MethodHandle by lazy {
        NativeDowncalls.bindCriticalHeap("unpack_longs_to_longs", bitPackDescriptor)
    }

    private val packLongsToLongsHandle: MethodHandle by lazy {
        NativeDowncalls.bindCriticalHeap("pack_longs_to_longs", bitPackDescriptor)
    }

    fun unpackLongsToShorts(
        sourceLongs: MemorySegment,
        longsCount: Long,
        destinationShorts: MemorySegment,
        elementCount: Long,
        bitsPerEntry: Int,
        precise: Boolean,
        bigEndianSource: Boolean,
    ): Long = unpackLongsToShortsHandle.invokeExact(
        sourceLongs,
        longsCount,
        destinationShorts,
        elementCount,
        bitsPerEntry,
        precise,
        bigEndianSource,
    ) as Long

    fun packShortsToLongs(
        sourceShorts: MemorySegment,
        elementCount: Long,
        destinationLongs: MemorySegment,
        longsCount: Long,
        bitsPerEntry: Int,
        precise: Boolean,
        bigEndianDestination: Boolean,
    ): Long = packShortsToLongsHandle.invokeExact(
        sourceShorts,
        elementCount,
        destinationLongs,
        longsCount,
        bitsPerEntry,
        precise,
        bigEndianDestination,
    ) as Long

    fun unpackLongsToLongs(
        sourceLongs: MemorySegment,
        longsCount: Long,
        destinationLongs: MemorySegment,
        elementCount: Long,
        bitsPerEntry: Int,
        precise: Boolean,
        bigEndianSource: Boolean,
    ): Long = unpackLongsToLongsHandle.invokeExact(
        sourceLongs,
        longsCount,
        destinationLongs,
        elementCount,
        bitsPerEntry,
        precise,
        bigEndianSource,
    ) as Long

    fun packLongsToLongs(
        sourceLongs: MemorySegment,
        elementCount: Long,
        destinationLongs: MemorySegment,
        longsCount: Long,
        bitsPerEntry: Int,
        precise: Boolean,
        bigEndianDestination: Boolean,
    ): Long = packLongsToLongsHandle.invokeExact(
        sourceLongs,
        elementCount,
        destinationLongs,
        longsCount,
        bitsPerEntry,
        precise,
        bigEndianDestination,
    ) as Long

}
