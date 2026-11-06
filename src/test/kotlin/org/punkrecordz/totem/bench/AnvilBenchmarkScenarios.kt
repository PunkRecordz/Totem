package org.punkrecordz.totem.bench

import org.punkrecordz.totem.anvil.AnvilRegionFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories

internal class AnvilBenchmarkScenarios(
    private val workload: AnvilWorkload,
    private val threadCount: Int,
) {

    private val sink: AtomicLong = AtomicLong()

    fun register(): List<BenchmarkScenario> = listOf(
        BenchmarkScenario("read slice 1T", parallel = false) { regionIndex -> readSlices(regionIndex) },
        BenchmarkScenario("read slice ${threadCount}T", parallel = true) { regionIndex -> readSlices(regionIndex) },
        BenchmarkScenario("read+parse 1T", parallel = false) { regionIndex -> readAndParse(regionIndex) },
        BenchmarkScenario("read+parse ${threadCount}T", parallel = true) { regionIndex -> readAndParse(regionIndex) },
        BenchmarkScenario("batch read 1T caller", parallel = false) { regionIndex ->
            batchRead(regionIndex, nativeParallel = true)
        },
        BenchmarkScenario("batch read+parse 1T caller", parallel = false) { regionIndex ->
            batchReadAndParse(regionIndex)
        },
        BenchmarkScenario("batch read ${threadCount}T", parallel = true) { regionIndex ->
            batchRead(regionIndex, nativeParallel = false)
        },
        BenchmarkScenario("write raw 1T", parallel = false) { regionIndex ->
            writeRaw("write1t", regionIndex, staged = false)
        },
        BenchmarkScenario("write raw ${threadCount}T", parallel = true) { regionIndex ->
            writeRaw("writeNt", regionIndex, staged = false)
        },
        BenchmarkScenario("write staged 1T caller", parallel = false) { regionIndex ->
            writeRaw("staged1t", regionIndex, staged = true)
        },
        BenchmarkScenario("copy decode+encode ${threadCount}T", parallel = true) { regionIndex ->
            copyDecodeEncode("copyNt", regionIndex)
        },
        BenchmarkScenario("copy batch+staged 1T caller", parallel = false) { regionIndex ->
            copyBatchStaged("copyBatch1t", regionIndex)
        },
        BenchmarkScenario("copy passthrough ${threadCount}T", parallel = true) { regionIndex ->
            copyPassthrough("passthroughNt", regionIndex)
        },
    )

    fun run(
        scenarios: List<BenchmarkScenario>,
        rounds: Int,
    ): List<BenchmarkResult> = scenarios.map { scenario ->
        executeScenario(scenario)

        val bestNanos = (0 until rounds).minOf { executeScenario(scenario) }
        BenchmarkResult(scenario.name, bestNanos, workload.totalChunks, workload.averageRawChunkBytes)
    }

    fun consumedChecksum(): Long = sink.get()

    private fun executeScenario(scenario: BenchmarkScenario): Long {
        val startNanos = System.nanoTime()

        when (scenario.parallel) {
            true -> Executors.newFixedThreadPool(threadCount).use { executor ->
                val futures = (0 until workload.regionCount).map { regionIndex ->
                    executor.submit { scenario.regionAction(regionIndex) }
                }
                futures.forEach { future -> future.get() }
            }
            false -> for (regionIndex in 0 until workload.regionCount) {
                scenario.regionAction(regionIndex)
            }
        }

        return System.nanoTime() - startNanos
    }

    private fun readSlices(regionIndex: Int) {
        var checksum = 0L

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { regionFile ->
            for (chunkIndex in 0 until AnvilWorkload.CHUNKS_PER_REGION) {
                val slice = regionFile.readDecompressedChunkSlice(chunkIndex and 31, chunkIndex ushr 5) ?: continue
                checksum += slice.byteSize()
            }
        }

        sink.addAndGet(checksum)
    }

    private fun readAndParse(regionIndex: Int) {
        var checksum = 0L

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { regionFile ->
            for (chunkIndex in 0 until AnvilWorkload.CHUNKS_PER_REGION) {
                val (_, compound) = regionFile.readChunkTransient(chunkIndex and 31, chunkIndex ushr 5) ?: continue
                checksum += (compound.getInt("xPos") ?: 0) + (compound["sections"]?.let { 1 } ?: 0)
            }
        }

        sink.addAndGet(checksum)
    }

    private fun batchRead(
        regionIndex: Int,
        nativeParallel: Boolean,
    ) {
        var checksum = 0L

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { regionFile ->
            regionFile.forEachDecompressedChunk(nativeParallel) { _, _, uncompressedNbt ->
                checksum += uncompressedNbt.byteSize()
            }
        }

        sink.addAndGet(checksum)
    }

    private fun batchReadAndParse(regionIndex: Int) {
        var checksum = 0L

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { regionFile ->
            regionFile.forEachChunkTransient { _, _, _, compound ->
                checksum += (compound.getInt("xPos") ?: 0) + (compound["sections"]?.let { 1 } ?: 0)
            }
        }

        sink.addAndGet(checksum)
    }

    private fun writeRaw(
        scenario: String,
        regionIndex: Int,
        staged: Boolean,
    ) {
        val targetPath = prepareTarget(scenario, regionIndex)

        AnvilRegionFile(
            targetPath.toFile(),
            writeMode = true,
            truncateExisting = true,
            parallelCompression = staged,
        ).use { regionFile ->
            for (chunkIndex in 0 until AnvilWorkload.CHUNKS_PER_REGION) {
                regionFile.writeRawChunkNbt(chunkIndex and 31, chunkIndex ushr 5, workload.templateFor(chunkIndex))
            }
        }
    }

    private fun copyBatchStaged(
        scenario: String,
        regionIndex: Int,
    ) {
        val targetPath = prepareTarget(scenario, regionIndex)

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { sourceRegion ->
            AnvilRegionFile(
                targetPath.toFile(),
                writeMode = true,
                truncateExisting = true,
                parallelCompression = true,
            ).use { targetRegion ->
                sourceRegion.forEachDecompressedChunk { chunkX, chunkZ, uncompressedNbt ->
                    targetRegion.writeRawChunkNbt(chunkX, chunkZ, uncompressedNbt)
                }
            }
        }
    }

    private fun copyPassthrough(
        scenario: String,
        regionIndex: Int,
    ) {
        val targetPath = prepareTarget(scenario, regionIndex)

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { sourceRegion ->
            AnvilRegionFile(targetPath.toFile(), writeMode = true, truncateExisting = true).use { targetRegion ->
                for (chunkIndex in 0 until AnvilWorkload.CHUNKS_PER_REGION) {
                    sourceRegion.copyChunkTo(targetRegion, chunkIndex and 31, chunkIndex ushr 5)
                }
            }
        }
    }

    private fun copyDecodeEncode(
        scenario: String,
        regionIndex: Int,
    ) {
        val targetPath = prepareTarget(scenario, regionIndex)

        AnvilRegionFile(workload.sourceRegionPath(regionIndex).toFile()).use { sourceRegion ->
            AnvilRegionFile(targetPath.toFile(), writeMode = true, truncateExisting = true).use { targetRegion ->
                for (chunkIndex in 0 until AnvilWorkload.CHUNKS_PER_REGION) {
                    val localX = chunkIndex and 31
                    val localZ = chunkIndex ushr 5
                    val slice = sourceRegion.readDecompressedChunkSlice(localX, localZ) ?: continue
                    targetRegion.writeRawChunkNbt(localX, localZ, slice)
                }
            }
        }
    }

    private fun prepareTarget(
        scenario: String,
        regionIndex: Int,
    ): Path {
        val targetPath = workload.targetRegionPath(scenario, regionIndex)
        targetPath.parent.createDirectories()
        Files.deleteIfExists(targetPath)
        return targetPath
    }

}
