package org.punkrecordz.totem.bench

import org.punkrecordz.totem.Totem
import org.punkrecordz.totem.anvil.AnvilRegionFile
import org.punkrecordz.totem.io.compression.CompressionType
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path

internal class AnvilWorkload(
    val workingDirectory: Path,
    val regionCount: Int,
    templateCount: Int = 16,
) : AutoCloseable {

    private val templateArena: Arena = Arena.ofShared()

    val rawTemplates: List<MemorySegment> = (0 until templateCount).map { templateIndex ->
        val compound = RealisticChunkFactory.build(
            chunkX = templateIndex,
            chunkZ = templateIndex * 3,
            seed = 9_000L + templateIndex,
        )

        Totem.save("", compound, templateArena, CompressionType.NONE)
    }

    val averageRawChunkBytes: Long = rawTemplates.sumOf { template -> template.byteSize() } / rawTemplates.size

    val totalChunks: Int = regionCount * CHUNKS_PER_REGION

    fun sourceRegionPath(regionIndex: Int): Path = workingDirectory.resolve("source/r.$regionIndex.0.mca")

    fun targetRegionPath(
        scenario: String,
        regionIndex: Int,
    ): Path = workingDirectory.resolve("$scenario/r.$regionIndex.0.mca")

    fun templateFor(chunkIndex: Int): MemorySegment = rawTemplates[chunkIndex % rawTemplates.size]

    fun writeSourceWorld() {
        Files.createDirectories(workingDirectory.resolve("source"))

        for (regionIndex in 0 until regionCount) {
            AnvilRegionFile(
                sourceRegionPath(regionIndex).toFile(),
                writeMode = true,
                truncateExisting = true,
            ).use { regionFile ->
                for (chunkIndex in 0 until CHUNKS_PER_REGION) {
                    regionFile.writeRawChunkNbt(
                        chunkIndex and 31,
                        chunkIndex ushr 5,
                        templateFor(chunkIndex + regionIndex),
                    )
                }
            }
        }
    }

    fun totalSourceBytes(): Long =
        (0 until regionCount).sumOf { regionIndex -> Files.size(sourceRegionPath(regionIndex)) }

    override fun close() {
        templateArena.close()
    }

    companion object {

        const val CHUNKS_PER_REGION: Int = 1024

    }

}
