package org.punkrecordz.totem.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

object TotemBitPackSys {

    private val nativeLinker: Linker = Linker.nativeLinker()
    private val criticalHeapOption: Linker.Option = Linker.Option.critical(true)

    private fun bindCriticalHeap(
        symbolName: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle {
        val symbol = NativeLibraryLoader.symbolLookup.find(symbolName)
            .orElseThrow { UnsatisfiedLinkError("Symbol $symbolName not found in totem-sys native library") }

        return nativeLinker.downcallHandle(symbol, descriptor, criticalHeapOption)
    }

    private val unpackLongsToShortsHandle: MethodHandle by lazy {
        bindCriticalHeap(
            "unpack_longs_to_shorts",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_BOOLEAN,
            ),
        )
    }

    private val packShortsToLongsHandle: MethodHandle by lazy {
        bindCriticalHeap(
            "pack_shorts_to_longs",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_BOOLEAN,
            ),
        )
    }

    private val unpackLongsToLongsHandle: MethodHandle by lazy {
        bindCriticalHeap(
            "unpack_longs_to_longs",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_BOOLEAN,
            ),
        )
    }

    private val packLongsToLongsHandle: MethodHandle by lazy {
        bindCriticalHeap(
            "pack_longs_to_longs",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_BOOLEAN,
            ),
        )
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
