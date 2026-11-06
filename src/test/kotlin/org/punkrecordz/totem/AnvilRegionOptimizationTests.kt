package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.anvil.AnvilRegionBufferPool
import org.punkrecordz.totem.anvil.AnvilRegionFile
import org.punkrecordz.totem.anvil.ChunkCoordinate
import org.punkrecordz.totem.bench.RealisticChunkFactory
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.Tags
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Random
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnvilRegionOptimizationTests {

    @Test
    fun testUnpaddedMcaCompressionCodesOneTwoThreeAndTransientReads() {
        val mcaPath = createTempFile("totem_unpadded_code3_", ".mca")

        try {
            val gzipTag = Tags.compound {
                putString("Codec", "GZIP")
                putInt("Marker", 101)
            }
            val zlibModernTag = RealisticChunkFactory.buildModernTestChunk(chunkX = 1, chunkZ = 0, seed = 777L)
            val uncompressedLargeTag = Tags.compound {
                putString("Codec", "NONE")
                putInt("Marker", 303)
                put("Payload", Tags.byteArray(ByteArray(5_000) { index -> (index and 0x7F).toByte() }))
            }

            val rawMcaBytes = McaTestFixture.buildMcaBytes(
                listOf(
                    McaFrameSpec(0, 0, 1, Totem.saveToByteArray("GzipRoot", gzipTag, CompressionType.GZIP)),
                    McaFrameSpec(1, 0, 2, Totem.saveToByteArray("", zlibModernTag, CompressionType.ZLIB)),
                    McaFrameSpec(31, 31, 3, Totem.saveToByteArray("RawRoot", uncompressedLargeTag, CompressionType.NONE), padSector = false),
                ),
            )
            assertTrue(rawMcaBytes.size % 4096 != 0, "Expected unpadded .mca file size not aligned to 4096 bytes")
            Files.write(mcaPath, rawMcaBytes)

            val escapedCompound: CompoundTag
            Arena.ofConfined().use { callerArena ->
                AnvilRegionFile(mcaPath.toFile(), writeMode = false).use { regionFile ->
                    // file channel is already closed during init, so no os mmap lock remains on windows
                    val renamedPath = mcaPath.resolveSibling("${mcaPath.fileName}.moved")
                    Files.move(mcaPath, renamedPath)
                    Files.move(renamedPath, mcaPath)

                    assertEquals(
                        setOf(ChunkCoordinate(0, 0), ChunkCoordinate(1, 0), ChunkCoordinate(31, 31)),
                        regionFile.listChunks().toSet(),
                    )

                    val (gzipName, gzipLoaded) = assertNotNull(regionFile.readChunkTransient(0, 0))
                    assertEquals("GzipRoot", gzipName)
                    assertEquals(101, gzipLoaded.getInt("Marker"))

                    val zlibSlice = assertNotNull(regionFile.readDecompressedChunkSlice(33, -32))
                    assertTrue(zlibSlice.byteSize() > 102_400L)
                    assertEquals(4189, Totem.parseRootCompound(zlibSlice).second.getInt("DataVersion"))

                    val (noneTransientName, noneTransientRoot) = assertNotNull(regionFile.readChunkTransient(-1, -1))
                    assertEquals("RawRoot", noneTransientName)
                    assertEquals(303, noneTransientRoot.getInt("Marker"))
                    assertEquals(5_000, noneTransientRoot.getByteArray("Payload")?.size)

                    val (noneOwnedName, noneOwnedRoot) = assertNotNull(regionFile.readChunk(63, 95, callerArena))
                    assertEquals("RawRoot", noneOwnedName)
                    escapedCompound = noneOwnedRoot
                }

                // overwrite pooled buffer via another region to verify caller arena copy does not alias
                McaTestFixture.withTempDirectory("totem_pool_overwrite_") { directory ->
                    val otherMca = directory.resolve("r.0.0.mca").toFile()
                    AnvilRegionFile(otherMca, writeMode = true).use { writer ->
                        writer.writeChunk(0, 0, "Other", Tags.compound { putInt("Marker", 999) })
                    }
                    AnvilRegionFile(otherMca, writeMode = false).use { reader ->
                        assertNotNull(reader.readChunkTransient(0, 0))
                    }
                }

                assertEquals(303, escapedCompound.getInt("Marker"))
                assertEquals(5_000, escapedCompound.getByteArray("Payload")?.size)
            }
        } finally {
            mcaPath.deleteIfExists()
        }
    }

    @Test
    fun testSequentialBufferedWriteRawNbtFramedSectorsAndTruncateExisting() {
        McaTestFixture.withTempDirectory("totem_seq_write_") { directory ->
            val mcaPath = directory.resolve("r.seq.mca")
            val donorMcaPath = directory.resolve("r.donor.mca")

            AnvilRegionBufferPool.clear()
            val modernChunk = RealisticChunkFactory.buildModernTestChunk(5, 7, seed = 42L)
            val rawTag = Tags.compound {
                putString("Source", "RawNbtSegment")
                putInt("Answer", 42)
            }
            val donorTag = Tags.compound {
                putString("Source", "FramedSectors")
                putInt("DonorId", 777)
            }

            AnvilRegionFile(donorMcaPath.toFile(), writeMode = true).use { donorRegion ->
                donorRegion.writeChunk(0, 0, "DonorRoot", donorTag)
            }
            val donorBytes = Files.readAllBytes(donorMcaPath)
            val donorFramedSectors = donorBytes.copyOfRange(8192, donorBytes.size)
            assertEquals(0, donorFramedSectors.size % 4096)

            Arena.ofConfined().use { arena ->
                val rawNbtSegment = Totem.save("RawNbtRoot", rawTag, arena, CompressionType.NONE)

                AnvilRegionFile(mcaPath.toFile(), writeMode = true).use { regionFile ->
                    regionFile.writeChunk(5, 7, "", modernChunk)
                    regionFile.writeRawChunkNbt(2, 3, rawNbtSegment)
                    regionFile.writeFramedChunkSectors(9, 11, MemorySegment.ofArray(donorFramedSectors))
                    assertEquals(0L, Files.size(mcaPath))

                    assertEquals(4189, regionFile.readChunkTransient(5, 7)?.second?.getInt("DataVersion"))
                    val (inMemoryRawName, inMemoryRaw) = assertNotNull(regionFile.readChunk(2, 3, arena))
                    assertEquals("RawNbtRoot", inMemoryRawName)
                    assertEquals(42, inMemoryRaw.getInt("Answer"))
                }

                assertTrue(AnvilRegionBufferPool.pooledBufferCount() >= 1)
                assertTrue(Files.size(mcaPath) >= 8192L + 3 * 4096L)

                AnvilRegionFile(mcaPath.toFile(), writeMode = false).use { reader ->
                    assertEquals(3, reader.listChunks().size)
                    assertEquals(4189, reader.readChunkTransient(5, 7)?.second?.getInt("DataVersion"))
                    assertEquals(42, reader.readChunkTransient(2, 3)?.second?.getInt("Answer"))
                    val (framedName, framedCompound) = assertNotNull(reader.readChunkTransient(9, 11))
                    assertEquals("DonorRoot", framedName)
                    assertEquals(777, framedCompound.getInt("DonorId"))
                }

                AnvilRegionFile(mcaPath.toFile(), writeMode = true, truncateExisting = true).use { truncatedRegion ->
                    truncatedRegion.writeChunk(0, 0, "Fresh", Tags.compound { putInt("OnlyOne", 1) })
                }

                assertEquals(8192L + 4096L, Files.size(mcaPath))
                AnvilRegionFile(mcaPath.toFile(), writeMode = false).use { reader ->
                    assertEquals(listOf(ChunkCoordinate(0, 0)), reader.listChunks())
                    assertNull(reader.readChunkTransient(5, 7))
                    assertEquals(1, reader.readChunkTransient(0, 0)?.second?.getInt("OnlyOne"))
                }
            }
        }
    }

    @Test
    fun testInPlaceUpdateDeferredHeaderFlushAndSectorHintReuse() {
        McaTestFixture.withTempDirectory("totem_inplace_") { directory ->
            val mcaPath = directory.resolve("r.inplace.mca")

            AnvilRegionFile(mcaPath.toFile(), writeMode = true).use { initialWriter ->
                for (index in 0 until 6) {
                    initialWriter.writeChunk(index, 0, "Root", Tags.compound { putInt("Step", index) })
                }
            }

            AnvilRegionFile(mcaPath.toFile(), writeMode = true).use { inPlaceRegion ->
                inPlaceRegion.writeChunk(10, 10, "Added", Tags.compound { putInt("Step", 100) })

                val chunk10Offset = (10 + 10 * 32) * 4
                val beforeFlush = ByteBuffer.wrap(Files.readAllBytes(mcaPath), 0, 8192).order(ByteOrder.BIG_ENDIAN).getInt(chunk10Offset)
                assertEquals(0, beforeFlush)

                inPlaceRegion.flush()
                val afterFlush = ByteBuffer.wrap(Files.readAllBytes(mcaPath), 0, 8192).order(ByteOrder.BIG_ENDIAN).getInt(chunk10Offset)
                assertTrue(afterFlush != 0)

                inPlaceRegion.writeChunk(1, 0, "Root", Tags.compound { putInt("Step", 111) })
                inPlaceRegion.writeChunk(11, 11, "Reused", Tags.compound { putInt("Step", 222) })
            }

            AnvilRegionFile(mcaPath.toFile(), writeMode = false).use { reader ->
                assertEquals(8, reader.listChunks().size)
                assertEquals(0, reader.readChunkTransient(0, 0)?.second?.getInt("Step"))
                assertEquals(111, reader.readChunkTransient(1, 0)?.second?.getInt("Step"))
                assertEquals(100, reader.readChunkTransient(10, 10)?.second?.getInt("Step"))
                assertEquals(222, reader.readChunkTransient(11, 11)?.second?.getInt("Step"))
            }
        }
    }

    @Test
    fun testSequentialBufferedWriteOverflowBeyond8MiB() {
        McaTestFixture.withTempDirectory("totem_overflow_8mb_") { directory ->
            val mcaPath = directory.resolve("r.overflow.mca")
            val random = Random(987654321L)

            AnvilRegionFile(mcaPath.toFile(), writeMode = true).use { writer ->
                for (chunkIndex in 0 until 12) {
                    val incompressibleBytes = ByteArray(800_000).also { bytes ->
                        random.nextBytes(bytes)
                        bytes[0] = chunkIndex.toByte()
                    }
                    writer.writeChunk(
                        chunkIndex,
                        0,
                        "Large",
                        Tags.compound {
                            putInt("ChunkId", chunkIndex)
                            put("Blob", Tags.byteArray(incompressibleBytes))
                        },
                    )
                }

                assertEquals(0, writer.readChunkTransient(0, 0)?.second?.getInt("ChunkId"))
                assertEquals(11, writer.readChunkTransient(11, 0)?.second?.getInt("ChunkId"))
            }

            assertTrue(Files.size(mcaPath) > AnvilRegionBufferPool.DEFAULT_BUFFER_BYTES)

            AnvilRegionFile(mcaPath.toFile(), writeMode = false).use { reader ->
                assertEquals(12, reader.listChunks().size)
                for (chunkIndex in 0 until 12) {
                    val (_, chunk) = assertNotNull(reader.readChunkTransient(chunkIndex, 0))
                    assertEquals(chunkIndex, chunk.getInt("ChunkId"))
                    assertEquals(chunkIndex.toByte(), chunk.getByteArray("Blob")!![0])
                }
            }
        }
    }

}
