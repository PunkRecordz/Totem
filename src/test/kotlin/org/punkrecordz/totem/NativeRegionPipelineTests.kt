package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.anvil.AnvilRegionFile
import org.punkrecordz.totem.anvil.CompressedChunkPayload
import org.punkrecordz.totem.bench.RealisticChunkFactory
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.Tags
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRegionPipelineTests {

    @Test
    fun testBatchInflateMatchesScalarReadsForEveryCodecInParallelAndSequentialModes() {
        McaTestFixture.withTempDirectory("totem_native_pipeline_") { directory ->
            val mcaPath = directory.resolve("r.0.0.mca")
            val expectedChunks = HashMap<Int, ByteArray>()
            val frames = ArrayList<McaFrameSpec>()
            val chunkIndices = (0 until 1024 step 3).toList()

            chunkIndices.forEachIndexed { listPosition, chunkIndex ->
                val compound = RealisticChunkFactory.buildModernTestChunk(
                    chunkX = chunkIndex and 31,
                    chunkZ = chunkIndex ushr 5,
                    seed = chunkIndex.toLong(),
                )
                val rawNbt = Totem.saveToByteArray("chunk-$chunkIndex", compound, CompressionType.NONE)
                val (compressionCode, payload) = when ((chunkIndex / 3) % 3) {
                    0 -> 2 to Totem.saveToByteArray("chunk-$chunkIndex", compound, CompressionType.ZLIB)
                    1 -> 1 to Totem.saveToByteArray("chunk-$chunkIndex", compound, CompressionType.GZIP)
                    else -> 3 to rawNbt
                }

                expectedChunks[chunkIndex] = rawNbt
                frames.add(
                    McaFrameSpec(
                        chunkIndex = chunkIndex,
                        compressionCode = compressionCode,
                        payload = payload,
                        padSector = listPosition < chunkIndices.lastIndex,
                    ),
                )
            }

            Files.write(mcaPath, McaTestFixture.buildMcaBytes(frames))

            for (parallel in listOf(true, false)) {
                val visitedChunks = HashMap<Int, ByteArray>()

                AnvilRegionFile(mcaPath.toFile()).use { regionFile ->
                    regionFile.forEachDecompressedChunk(parallel) { chunkX, chunkZ, uncompressedNbt ->
                        val scalarSlice = assertNotNull(regionFile.readDecompressedChunkSlice(chunkX, chunkZ))
                        val batchBytes = uncompressedNbt.toArray(ValueLayout.JAVA_BYTE)

                        assertContentEquals(scalarSlice.toArray(ValueLayout.JAVA_BYTE), batchBytes)
                        visitedChunks[chunkX or (chunkZ shl 5)] = batchBytes
                    }
                }

                assertEquals(expectedChunks.keys, visitedChunks.keys)
                expectedChunks.forEach { (chunkIndex, rawNbt) -> assertContentEquals(rawNbt, visitedChunks[chunkIndex]) }
            }

            AnvilRegionFile(mcaPath.toFile()).use { regionFile ->
                var parsedChunks = 0
                regionFile.forEachChunkTransient { chunkX, chunkZ, rootName, compound ->
                    assertEquals("chunk-${chunkX or (chunkZ shl 5)}", rootName)
                    assertEquals(chunkX, compound.getInt("xPos"))
                    parsedChunks += 1
                }
                assertEquals(expectedChunks.size, parsedChunks)
            }
        }
    }

    @Test
    fun testBatchInflateReplaysCorruptChunksThroughScalarErrors() {
        McaTestFixture.withTempDirectory("totem_corrupt_replay_") { directory ->
            val mcaPath = directory.resolve("r.corrupt.mca")
            val validPayload = Totem.saveToByteArray("ok", Tags.compound { putInt("Valid", 1) }, CompressionType.ZLIB)
            val corruptPayload = byteArrayOf(0x78, 0x9C.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)

            Files.write(
                mcaPath,
                McaTestFixture.buildMcaBytes(
                    listOf(
                        McaFrameSpec(chunkIndex = 0, compressionCode = 2, payload = validPayload),
                        McaFrameSpec(chunkIndex = 1, compressionCode = 2, payload = corruptPayload),
                    ),
                ),
            )

            AnvilRegionFile(mcaPath.toFile()).use { regionFile ->
                val visited = ArrayList<Int>()
                val scalarFailure = assertFailsWith<IllegalArgumentException> {
                    regionFile.readDecompressedChunkSlice(1, 0)
                }
                val batchFailure = assertFailsWith<IllegalArgumentException> {
                    regionFile.forEachDecompressedChunk { chunkX, _, _ -> visited.add(chunkX) }
                }

                assertEquals(listOf(0), visited)
                assertEquals(scalarFailure.message, batchFailure.message)
            }
        }
    }

    @Test
    fun testParallelCompressionStagingRoundTripLastWriteWinsAndReadYourWrites() {
        McaTestFixture.withTempDirectory("totem_staging_") { directory ->
            val mcaPath = directory.resolve("r.parallel.mca")

            Arena.ofConfined().use { arena ->
                AnvilRegionFile(mcaPath.toFile(), writeMode = true, parallelCompression = true).use { regionFile ->
                    for (chunkIndex in 0 until 1024) {
                        val compound = RealisticChunkFactory.buildModernTestChunk(
                            chunkX = chunkIndex and 31,
                            chunkZ = chunkIndex ushr 5,
                            seed = 7L,
                        )
                        when (chunkIndex % 2) {
                            0 -> regionFile.writeChunk(chunkIndex and 31, chunkIndex ushr 5, "root", compound)
                            else -> regionFile.writeRawChunkNbt(
                                chunkIndex and 31,
                                chunkIndex ushr 5,
                                Totem.save("root", compound, arena, CompressionType.NONE),
                            )
                        }
                    }

                    // staged version 1 then staged version 2 of the same chunk: the later one must win
                    regionFile.writeChunk(3, 3, "v1", Tags.compound { putInt("Version", 1) })
                    regionFile.writeChunk(3, 3, "v2", Tags.compound { putInt("Version", 2) })

                    // staged version then a direct compressed write of the same chunk: the direct write must win
                    regionFile.writeChunk(4, 4, "staged", Tags.compound { putInt("Version", 1) })
                    val directPayload = Totem.save(
                        "direct",
                        Tags.compound { putInt("Version", 9) },
                        arena,
                        CompressionType.ZLIB,
                    )
                    regionFile.writeCompressedChunk(
                        4,
                        4,
                        CompressedChunkPayload(CompressedChunkPayload.COMPRESSION_ZLIB, directPayload),
                    )

                    assertEquals(2, regionFile.readChunkTransient(3, 3)?.second?.getInt("Version"))
                    assertEquals(1024, regionFile.listChunks().size)
                }
            }

            AnvilRegionFile(mcaPath.toFile()).use { reader ->
                assertEquals(1024, reader.listChunks().size)
                assertEquals("v2", reader.readChunkTransient(3, 3)?.first)
                assertEquals(9, reader.readChunkTransient(4, 4)?.second?.getInt("Version"))

                var verified = 0
                reader.forEachChunkTransient { chunkX, chunkZ, rootName, compound ->
                    if (rootName == "root") {
                        assertEquals(chunkX, compound.getInt("xPos"))
                        assertEquals(chunkZ, compound.getInt("zPos"))
                        verified += 1
                    }
                }
                assertEquals(1022, verified)
            }
        }
    }

    @Test
    fun testParallelCompressionInPlaceUpdateAndCompressedPassthrough() {
        McaTestFixture.withTempDirectory("totem_inplace_copy_") { directory ->
            val mcaPath = directory.resolve("r.inplace.mca")
            val targetPath = directory.resolve("r.target.mca")

            AnvilRegionFile(mcaPath.toFile(), writeMode = true, compressionLevel = 9).use { writer ->
                for (chunkX in 0 until 8) {
                    writer.writeChunk(chunkX, 0, "base", Tags.compound { putInt("Step", chunkX) })
                }
            }

            AnvilRegionFile(mcaPath.toFile(), writeMode = true, parallelCompression = true, compressionLevel = 6).use { updater ->
                updater.writeChunk(2, 0, "updated", Tags.compound { putInt("Step", 200) })
                updater.writeChunk(20, 20, "added", Tags.compound { putInt("Step", 2020) })
                updater.flush()
                updater.writeChunk(5, 0, "updated", Tags.compound { putInt("Step", 500) })
            }

            AnvilRegionFile(mcaPath.toFile()).use { source ->
                assertEquals(9, source.listChunks().size)
                assertEquals(200, source.readChunkTransient(2, 0)?.second?.getInt("Step"))
                assertEquals(500, source.readChunkTransient(5, 0)?.second?.getInt("Step"))
                assertEquals(2020, source.readChunkTransient(20, 20)?.second?.getInt("Step"))

                AnvilRegionFile(targetPath.toFile(), writeMode = true).use { target ->
                    assertTrue(source.copyChunkTo(target, 0, 0))
                    assertTrue(source.copyChunkTo(target, 2, 0))
                    assertTrue(!source.copyChunkTo(target, 31, 31))
                }
            }

            AnvilRegionFile(mcaPath.toFile()).use { source ->
                AnvilRegionFile(targetPath.toFile()).use { target ->
                    val sourcePayload = assertNotNull(source.readCompressedChunk(0, 0))
                    val targetPayload = assertNotNull(target.readCompressedChunk(0, 0))
                    assertContentEquals(
                        sourcePayload.payload.toArray(ValueLayout.JAVA_BYTE),
                        targetPayload.payload.toArray(ValueLayout.JAVA_BYTE),
                    )
                    assertNull(target.readCompressedChunk(31, 31))
                }
            }
        }
    }

    @Test
    fun testCompressionLevelValidationAndExtremeLevelsStayReadable() {
        McaTestFixture.withTempDirectory("totem_levels_") { directory ->
            assertFailsWith<IllegalArgumentException> {
                AnvilRegionFile(directory.resolve("r.bad.mca").toFile(), writeMode = true, compressionLevel = 13)
            }
            assertFailsWith<IllegalArgumentException> {
                CompressedChunkPayload(compressionCode = 4, payload = MemorySegment.ofArray(byteArrayOf(1)))
            }

            for (level in intArrayOf(0, 12)) {
                val mcaPath = directory.resolve("r.level$level.mca")
                AnvilRegionFile(mcaPath.toFile(), writeMode = true, compressionLevel = level).use { writer ->
                    writer.writeChunk(1, 1, "level", Tags.compound { putInt("Level", level) })
                }
                AnvilRegionFile(mcaPath.toFile()).use { reader ->
                    assertEquals(level, reader.readChunkTransient(1, 1)?.second?.getInt("Level"))
                }
            }
        }
    }

}
