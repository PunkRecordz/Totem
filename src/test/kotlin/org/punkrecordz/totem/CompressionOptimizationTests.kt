package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.bench.RealisticChunkFactory
import org.punkrecordz.totem.io.compression.CompressionScratch
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.io.compression.GzipCompression
import org.punkrecordz.totem.io.compression.NoneCompression
import org.punkrecordz.totem.io.compression.ZlibCompression
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompressionOptimizationTests {

    @Test
    fun testZlibModernChunkOver100KilobytesSinglePassAndTransientSlice() {
        val chunkRoot = RealisticChunkFactory.buildModernTestChunk()

        Arena.ofConfined().use { rawArena ->
            val rawChunkNbt = Totem.save("", chunkRoot, rawArena, CompressionType.NONE)
            assertTrue(
                rawChunkNbt.byteSize() > 102_400L,
                "Expected realistic modern chunk NBT > 100 KiB, got ${rawChunkNbt.byteSize()} bytes",
            )

            val zlib = ZlibCompression(level = 1)

            Arena.ofConfined().use { compressionArena ->
                val compressedChunk = zlib.compress(rawChunkNbt, compressionArena)

                // modern 1.21 chunk compresses from > 102 KiB to ~6-15 KiB, so max(65536, inputSize * 4) < rawSize
                assertTrue(
                    compressedChunk.byteSize() * 4L < rawChunkNbt.byteSize() && compressedChunk.byteSize() < 16_384L,
                    "Expected realistic modern chunk ratio (< 16 KiB compressed for > 100 KiB NBT), got ${compressedChunk.byteSize()} bytes",
                )

                val scratch = CompressionScratch.get()
                assertEquals(0L, scratch.outputSegment.address() and 63L)

                val transientSlice = zlib.decompressTransient(compressedChunk)
                assertEquals(rawChunkNbt.byteSize(), transientSlice.byteSize())
                assertEquals(scratch.outputSegment.address(), transientSlice.address())

                val (transientName, transientTag) = Totem.parseRootCompound(transientSlice)
                assertEquals("", transientName)
                assertEquals(4189, transientTag.getInt("DataVersion"))

                val decompressedInArena = zlib.decompress(compressedChunk, compressionArena)
                assertEquals(rawChunkNbt.byteSize(), decompressedInArena.byteSize())
                assertEquals(-1L, rawChunkNbt.mismatch(decompressedInArena))
            }
        }
    }

    @Test
    fun testZlibScratchGrowthAndStreamingInflaterAbove16MiB() {
        val zlib = ZlibCompression(level = 1)

        // 3 mib payload exercises scratch growth (1 mib -> 2 mib -> 4 mib)
        val mediumBytes = ByteArray(3 * 1024 * 1024) { index -> ((index * 31 + (index ushr 8)) and 0x7F).toByte() }
        Arena.ofConfined().use { arena ->
            val mediumInput = MemorySegment.ofArray(mediumBytes)
            val compressedMedium = zlib.compress(mediumInput, arena)

            val transientMedium = zlib.decompressTransient(compressedMedium)
            assertEquals(mediumBytes.size.toLong(), transientMedium.byteSize())
            assertTrue(CompressionScratch.get().outputCapacity >= 4L * 1024L * 1024L)
            assertEquals(0L, CompressionScratch.get().outputSegment.address() and 63L)

            val decompressedMedium = zlib.decompress(compressedMedium, arena)
            assertEquals(-1L, mediumInput.mismatch(decompressedMedium))
        }

        // 18 mib payload (> 16 mib) exercises off-heap streaming inflater fallback without heap byte array streams
        val hugeSize = 18 * 1024 * 1024
        val hugeBytes = ByteArray(hugeSize) { index -> ((index ushr 4) xor (index and 0x1F)).toByte() }
        Arena.ofConfined().use { arena ->
            val hugeInput = MemorySegment.ofArray(hugeBytes)
            val compressedHuge = zlib.compress(hugeInput, arena)
            assertTrue(compressedHuge.byteSize() < 2L * 1024L * 1024L)

            val transientHuge = zlib.decompressTransient(compressedHuge)
            assertEquals(hugeSize.toLong(), transientHuge.byteSize())
            assertEquals(
                CompressionScratch.MAX_OUTPUT_CAPACITY_BYTES,
                CompressionScratch.get().outputCapacity,
            )

            val decompressedHuge = zlib.decompress(compressedHuge, arena)
            assertEquals(hugeSize.toLong(), decompressedHuge.byteSize())
            assertTrue(hugeBytes.contentEquals(decompressedHuge.toArray(ValueLayout.JAVA_BYTE)))
        }
    }

    @Test
    fun testCompressorLevelArrayAndGzipAndNoneTransientDecompression() {
        val samplePayload = ByteArray(140_000) { index -> ((index * 17) and 0xFF).toByte() }
        val heapInput = MemorySegment.ofArray(samplePayload)

        Arena.ofConfined().use { arena ->
            for (level in intArrayOf(0, 1, 6, 12)) {
                val zlib = ZlibCompression(level = level)
                val zlibCompressed = zlib.compress(heapInput, arena)
                val zlibTransient = zlib.decompressTransient(zlibCompressed)
                assertEquals(samplePayload.size.toLong(), zlibTransient.byteSize())
                assertTrue(samplePayload.contentEquals(zlibTransient.toArray(ValueLayout.JAVA_BYTE)))

                val gzip = GzipCompression(level = level)
                val gzipCompressed = gzip.compress(heapInput, arena)
                val gzipTransient = gzip.decompressTransient(gzipCompressed)
                assertEquals(samplePayload.size.toLong(), gzipTransient.byteSize())
                assertTrue(samplePayload.contentEquals(gzipTransient.toArray(ValueLayout.JAVA_BYTE)))
            }

            // multi-member gzip transient decompression
            val multiMemberPayload = ByteArray(1_500_000) { index -> ((index * 7) and 0xFF).toByte() }
            val multiCompressed = GzipCompression(level = 1).compress(MemorySegment.ofArray(multiMemberPayload), arena)
            val multiTransient = CompressionType.GZIP.decompressTransient(multiCompressed)
            assertEquals(multiMemberPayload.size.toLong(), multiTransient.byteSize())
            assertTrue(multiMemberPayload.contentEquals(multiTransient.toArray(ValueLayout.JAVA_BYTE)))

            // none compression transient returns a native slice even when given a heap segment
            val noneTransientFromHeap = NoneCompression.decompressTransient(heapInput)
            assertTrue(noneTransientFromHeap.isNative)
            assertEquals(samplePayload.size.toLong(), noneTransientFromHeap.byteSize())

            assertFailsWith<IllegalArgumentException> {
                ZlibCompression(level = 13).compress(heapInput, arena)
            }

            assertFailsWith<IllegalArgumentException> {
                CompressionType.ZLIB.decompressTransient(
                    MemorySegment.ofArray(byteArrayOf(0x78, 0x9C.toByte(), 0xFF.toByte(), 0x00)),
                )
            }
        }
    }

}
