package org.punkrecordz.totem.ffi

import java.lang.foreign.MemorySegment

internal object TotemCodecSys {

    const val FORMAT_RAW: Int = 0
    const val FORMAT_ZLIB: Int = 1
    const val FORMAT_GZIP: Int = 2

    const val STATUS_BAD_DATA: Long = -1L
    const val STATUS_INSUFFICIENT_SPACE: Long = -2L
    const val STATUS_INVALID_ARGUMENT: Long = -3L
    const val STATUS_CHUNK_TOO_LARGE: Long = -5L

    const val MIN_LEVEL: Int = 0
    const val MAX_LEVEL: Int = 12

    // critical downcalls are restricted to fast levels and bounded inputs so safepoint latency stays sub-millisecond
    private const val CRITICAL_INFLATE_OUTPUT_LIMIT_BYTES: Long = 2L * 1024L * 1024L
    private const val CRITICAL_DEFLATE_INPUT_LIMIT_BYTES: Long = 256L * 1024L
    private const val MAX_CRITICAL_DEFLATE_LEVEL: Int = 4

    val nativeParallelism: Int by lazy {
        TotemCodecBindings.nativeParallelism.invokeExact() as Int
    }

    fun inflate(
        format: Int,
        source: MemorySegment,
        destination: MemorySegment,
        consumedBytesOut: MemorySegment = MemorySegment.NULL,
    ): Long {
        requireNative(source, "inflate source")
        requireNative(destination, "inflate destination")

        val handle = when {
            destination.byteSize() <= CRITICAL_INFLATE_OUTPUT_LIMIT_BYTES -> TotemCodecBindings.inflateCritical
            else -> TotemCodecBindings.inflateStandard
        }

        return handle.invokeExact(
            format,
            source,
            source.byteSize(),
            destination,
            destination.byteSize(),
            consumedBytesOut,
        ) as Long
    }

    fun deflate(
        format: Int,
        level: Int,
        source: MemorySegment,
        destination: MemorySegment,
    ): Long {
        requireLevel(level)
        requireNative(source, "deflate source")
        requireNative(destination, "deflate destination")

        val handle = when {
            level <= MAX_CRITICAL_DEFLATE_LEVEL && source.byteSize() <= CRITICAL_DEFLATE_INPUT_LIMIT_BYTES ->
                TotemCodecBindings.deflateCritical
            else -> TotemCodecBindings.deflateStandard
        }

        return handle.invokeExact(
            format,
            level,
            source,
            source.byteSize(),
            destination,
            destination.byteSize(),
        ) as Long
    }

    fun deflateBound(
        format: Int,
        sourceLength: Long,
    ): Long = TotemCodecBindings.deflateBound.invokeExact(format, sourceLength) as Long

    fun frameBound(rawLength: Long): Long = TotemCodecBindings.frameBound.invokeExact(rawLength) as Long

    fun frameChunk(
        level: Int,
        source: MemorySegment,
        destination: MemorySegment,
    ): Long {
        requireLevel(level)
        requireNative(source, "frame source")
        requireNative(destination, "frame destination")

        val handle = when {
            level <= MAX_CRITICAL_DEFLATE_LEVEL && source.byteSize() <= CRITICAL_DEFLATE_INPUT_LIMIT_BYTES ->
                TotemCodecBindings.frameChunkCritical
            else -> TotemCodecBindings.frameChunkStandard
        }

        return handle.invokeExact(
            level,
            source,
            source.byteSize(),
            destination,
            destination.byteSize(),
        ) as Long
    }

    fun frameChunks(
        level: Int,
        sourceDescriptors: MemorySegment,
        chunkCount: Int,
        destination: MemorySegment,
        sectorCountsOut: MemorySegment,
        parallel: Boolean,
    ): Long {
        requireLevel(level)

        return TotemCodecBindings.frameChunks.invokeExact(
            level,
            sourceDescriptors,
            chunkCount,
            destination,
            destination.byteSize(),
            sectorCountsOut,
            if (parallel) 1 else 0,
        ) as Long
    }

    fun inflateChunks(
        region: MemorySegment,
        firstChunk: Int,
        chunkCount: Int,
        destination: MemorySegment,
        entriesOut: MemorySegment,
        parallel: Boolean,
    ): Long = TotemCodecBindings.inflateChunks.invokeExact(
        region,
        region.byteSize(),
        firstChunk,
        chunkCount,
        destination,
        destination.byteSize(),
        entriesOut,
        if (parallel) 1 else 0,
    ) as Long

    fun gzipSlicesBound(
        descriptors: MemorySegment,
        sliceCount: Int,
    ): Long = TotemCodecBindings.gzipSlicesBound.invokeExact(
        descriptors,
        sliceCount,
    ) as Long

    fun gzipCompressSlices(
        level: Int,
        descriptors: MemorySegment,
        sliceCount: Int,
        staging: MemorySegment,
    ): Long {
        requireLevel(level)
        requireNative(staging, "gzip staging")

        return TotemCodecBindings.gzipCompressSlices.invokeExact(
            level,
            descriptors,
            sliceCount,
            staging,
            staging.byteSize(),
        ) as Long
    }

    fun gzipInflateMembers(
        source: MemorySegment,
        memberOffsets: MemorySegment,
        memberCount: Int,
        destination: MemorySegment,
    ): Long {
        requireNative(source, "gzip source")
        requireNative(destination, "gzip destination")

        return TotemCodecBindings.gzipInflateMembers.invokeExact(
            source,
            source.byteSize(),
            memberOffsets,
            memberCount,
            destination,
            destination.byteSize(),
        ) as Long
    }

    private fun requireNative(
        segment: MemorySegment,
        role: String,
    ) {
        require(segment.isNative) {
            "Native codec $role must be an off-heap segment, but a heap segment of ${segment.byteSize()} bytes was supplied"
        }
    }

    private fun requireLevel(level: Int) {
        require(level in MIN_LEVEL..MAX_LEVEL) {
            "Compression level must be in $MIN_LEVEL..$MAX_LEVEL, but was $level"
        }
    }

}
