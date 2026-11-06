package org.punkrecordz.totem.bench

internal class BenchmarkResult(
    val name: String,
    val bestNanos: Long,
    val chunkCount: Int,
    val averageRawChunkBytes: Long,
) {

    val chunksPerSecond: Double
        get() = chunkCount / (bestNanos / 1e9)

    val rawGigabytesPerSecond: Double
        get() = chunkCount.toLong() * averageRawChunkBytes / (bestNanos / 1e9) / 1e9

}
