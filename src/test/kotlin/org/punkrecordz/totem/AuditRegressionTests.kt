package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.anvil.AnvilRegionBufferPool
import org.punkrecordz.totem.anvil.AnvilRegionFile
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.GzipCompression
import org.punkrecordz.totem.io.compression.ZlibCompression
import org.punkrecordz.totem.tag.Tags
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditRegressionTests {

    @Test
    fun testOversizedHeapSliceGzipStagingSurvivesGarbageCollection() {
        val gzip = GzipCompression(level = 1)
        val firstSliceBytes = ByteArray(9 * 1024 * 1024) { index -> ((index * 19) and 0xFF).toByte() }
        val secondSliceBytes = ByteArray(9 * 1024 * 1024) { index -> ((index * 37 + 5) and 0xFF).toByte() }

        Arena.ofConfined().use { arena ->
            val slices = listOf(MemorySegment.ofArray(firstSliceBytes), MemorySegment.ofArray(secondSliceBytes))
            System.gc()
            val compressed = gzip.compress(slices, arena)
            val decompressed = gzip.decompress(compressed, arena)

            assertEquals((firstSliceBytes.size + secondSliceBytes.size).toLong(), decompressed.byteSize())
            assertEquals(-1L, MemorySegment.ofArray(firstSliceBytes).mismatch(decompressed.asSlice(0L, firstSliceBytes.size.toLong())))
            assertEquals(
                -1L,
                MemorySegment.ofArray(secondSliceBytes).mismatch(
                    decompressed.asSlice(firstSliceBytes.size.toLong(), secondSliceBytes.size.toLong()),
                ),
            )
        }
    }

    @Test
    fun testStreamingFallbacksRejectTruncatedCorruptAndCrossFormatStreams() {
        val zlib = ZlibCompression(level = 1)
        val gzip = GzipCompression(level = 1)

        Arena.ofConfined().use { arena ->
            // 1. truncated > 16 mib zlib stream cut off mid-deflate must fail rather than return partial bytes
            val hugeRaw = ByteArray(18 * 1024 * 1024) { index -> ((index * 13 + (index ushr 7)) and 0x7F).toByte() }
            val compressedHugeZlib = zlib.compress(MemorySegment.ofArray(hugeRaw), arena).toArray(ValueLayout.JAVA_BYTE)
            val truncatedHugeZlib = compressedHugeZlib.copyOf(compressedHugeZlib.size - 128)
            assertFailsWith<IllegalArgumentException> {
                zlib.decompressTransient(MemorySegment.ofArray(truncatedHugeZlib))
            }

            // 2. valid zlib payload must never be accepted by gzip decompressor
            val validZlibBytes = zlib.compress(MemorySegment.ofArray(ByteArray(256) { index -> index.toByte() }), arena)
            assertFailsWith<IllegalArgumentException> {
                gzip.decompressTransient(validZlibBytes)
            }

            // 3. > 1024 gzip members exercises GzipStreamFallback directly; verify valid, truncated, bad crc, and bad isize
            val manyMembersStream = ByteArrayOutputStream()
            repeat(1030) { memberIndex ->
                GZIPOutputStream(manyMembersStream).use { memberOut ->
                    memberOut.write(byteArrayOf(memberIndex.toByte(), (memberIndex ushr 8).toByte()))
                }
            }
            val validManyMembers = manyMembersStream.toByteArray()
            assertEquals(2060L, gzip.decompressTransient(MemorySegment.ofArray(validManyMembers)).byteSize())

            val truncatedGzip = validManyMembers.copyOf(validManyMembers.size - 5)
            assertFailsWith<IllegalArgumentException> { gzip.decompressTransient(MemorySegment.ofArray(truncatedGzip)) }

            val badCrcGzip = validManyMembers.copyOf().also { bytes ->
                bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 0xFF).toByte()
            }
            assertFailsWith<IllegalArgumentException> { gzip.decompressTransient(MemorySegment.ofArray(badCrcGzip)) }

            val badIsizeGzip = validManyMembers.copyOf().also { bytes ->
                bytes[bytes.size - 4] = (bytes[bytes.size - 4].toInt() xor 0x01).toByte()
            }
            assertFailsWith<IllegalArgumentException> { gzip.decompressTransient(MemorySegment.ofArray(badIsizeGzip)) }
        }
    }

    @Test
    fun testSmallMultiMemberGzipAndSpoofedLargeTrailerDoNotOverAllocate() {
        val gzip = GzipCompression(level = 1)
        val multiOut = ByteArrayOutputStream()
        GZIPOutputStream(multiOut).use { first -> first.write(ByteArray(300) { 1 }) }
        GZIPOutputStream(multiOut).use { second -> second.write(ByteArray(500) { 2 }) }
        val smallMultiBytes = multiOut.toByteArray()
        assertTrue(smallMultiBytes.size < 64 * 1024)

        Arena.ofConfined().use { arena ->
            val restored = gzip.decompress(MemorySegment.ofArray(smallMultiBytes), arena)
            assertEquals(800L, restored.byteSize())
            assertEquals(1.toByte(), restored.get(ValueLayout.JAVA_BYTE, 0L))
            assertEquals(2.toByte(), restored.get(ValueLayout.JAVA_BYTE, 300L))

            val tinyValid = gzip.compress(MemorySegment.ofArray(ByteArray(64) { 7 }), arena).toArray(ValueLayout.JAVA_BYTE)
            MemorySegment.ofArray(tinyValid).set(
                ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                tinyValid.size.toLong() - 4L,
                Int.MAX_VALUE - 16,
            )
            assertFailsWith<IllegalArgumentException> { gzip.decompress(MemorySegment.ofArray(tinyValid), arena) }
        }
    }

    @Test
    fun testReadCompressedChunkDoesNotAliasPoolAndSelfCopyChunkSucceeds() {
        McaTestFixture.withTempDirectory("totem_h1_audit_") { directory ->
            val firstRegionFile = directory.resolve("r.0.0.mca").toFile()
            val overwriteRegionFile = directory.resolve("r.1.0.mca").toFile()

            AnvilRegionBufferPool.clear()
            AnvilRegionFile(firstRegionFile, writeMode = true).use { writer ->
                writer.writeChunk(2, 3, "Origin", Tags.compound { putInt("Token", 123456) })
            }

            val retainedPayload = AnvilRegionFile(firstRegionFile, writeMode = false).use { reader ->
                assertNotNull(reader.readCompressedChunk(2, 3))
            }
            val expectedBytes = retainedPayload.payload.toArray(ValueLayout.JAVA_BYTE)

            AnvilRegionFile(overwriteRegionFile, writeMode = true).use { writer ->
                writer.writeChunk(2, 3, "Overwrite", Tags.compound { putByteArray("Fill", ByteArray(4096) { 0x5A }) })
            }
            AnvilRegionFile(overwriteRegionFile, writeMode = false).use { reader ->
                assertNotNull(reader.readChunkTransient(2, 3))
            }

            assertContentEquals(expectedBytes, retainedPayload.payload.toArray(ValueLayout.JAVA_BYTE))

            AnvilRegionFile(firstRegionFile, writeMode = true).use { inPlace ->
                assertTrue(inPlace.copyChunkTo(inPlace, 2, 3))
                val copied = assertNotNull(inPlace.readCompressedChunk(2, 3))
                inPlace.writeCompressedChunk(9, 9, copied)
                assertEquals(123456, inPlace.readChunkTransient(9, 9)?.second?.getInt("Token"))
            }
        }
    }

    @Test
    fun testConcurrentReadOnlyBatchAndCloseIsRaceFreeAndClosedArenaDoesNotLeak() {
        val closedArena = Arena.ofConfined().also { arena -> arena.close() }
        assertFailsWith<IllegalStateException> { closedArena.allocateUninitialized(4L * 1024L * 1024L) }
        assertFailsWith<IllegalStateException> { closedArena.allocateUninitialized(16_384L) }

        McaTestFixture.withTempDirectory("totem_h2_close_race_") { directory ->
            val mcaFile = directory.resolve("r.race.mca").toFile()
            AnvilRegionFile(mcaFile, writeMode = true).use { writer ->
                for (chunkX in 0 until 32) {
                    writer.writeChunk(chunkX, 0, "c", Tags.compound { putInt("X", chunkX) })
                }
            }

            val unexpectedError = AtomicReference<Throwable?>(null)
            Executors.newFixedThreadPool(4).use { executor ->
                repeat(20) {
                    val region = AnvilRegionFile(mcaFile, writeMode = false)
                    val startLatch = CountDownLatch(1)
                    val doneLatch = CountDownLatch(3)

                    repeat(2) {
                        executor.submit {
                            try {
                                startLatch.await()
                                region.forEachChunkTransient { chunkX, _, _, compound ->
                                    if (compound.getInt("X") != chunkX) {
                                        throw AssertionError("Corrupted chunk data observed during concurrent close")
                                    }
                                }
                            } catch (_: IllegalStateException) {
                                // expected when close() wins the lock before read starts
                            } catch (failure: Throwable) {
                                unexpectedError.compareAndSet(null, failure)
                            } finally {
                                doneLatch.countDown()
                            }
                        }
                    }

                    executor.submit {
                        try {
                            startLatch.await()
                            region.close()
                        } catch (failure: Throwable) {
                            unexpectedError.compareAndSet(null, failure)
                        } finally {
                            doneLatch.countDown()
                        }
                    }

                    startLatch.countDown()
                    assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
                    assertNull(unexpectedError.get(), "Unexpected concurrent close failure: ${unexpectedError.get()}")
                }
            }
        }
    }

    @Test
    fun testSupersededOversizedChunkInSameStagingBatchDoesNotFailCommit() {
        McaTestFixture.withTempDirectory("totem_m2_staging_") { directory ->
            val mcaFile = directory.resolve("r.staging.mca").toFile()
            val incompressible = ByteArray(1_200_000).also { bytes -> Random(42L).nextBytes(bytes) }

            AnvilRegionFile(mcaFile, writeMode = true, parallelCompression = true).use { writer ->
                writer.writeChunk(0, 0, "oversized", Tags.compound { putByteArray("Huge", incompressible) })
                writer.writeChunk(0, 0, "replacement", Tags.compound { putInt("Small", 77) })
            }

            AnvilRegionFile(mcaFile, writeMode = false).use { reader ->
                val (name, tag) = assertNotNull(reader.readChunkTransient(0, 0))
                assertEquals("replacement", name)
                assertEquals(77, tag.getInt("Small"))
            }
        }
    }

}
