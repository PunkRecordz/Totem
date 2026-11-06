package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.GzipCompression
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelPipelineOptimizationTests {

    @Test
    fun testSlabPoolReusesLargeUninitializedAllocationsSafely() {
        val requestedBytes = 3L * 1024L * 1024L + 137L
        val firstAddress: Long

        Arena.ofConfined().use { firstArena ->
            val firstSegment = firstArena.allocateUninitialized(requestedBytes)
            assertEquals(requestedBytes, firstSegment.byteSize())
            firstSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, 0L, 0x1122334455667788L)
            firstAddress = firstSegment.address()
        }

        val closedView = Arena.ofConfined().let { closedArena ->
            val segment = closedArena.allocateUninitialized(requestedBytes)
            assertEquals(firstAddress, segment.address())
            assertEquals(requestedBytes, segment.byteSize())
            closedArena.close()
            segment
        }

        assertFailsWith<IllegalStateException> {
            closedView.get(ValueLayout.JAVA_BYTE, 0L)
        }
    }

    @Test
    fun testParallelGzipMultiSliceNativeAndHeapRoundTrip() {
        val gzip = GzipCompression(level = 2)
        val firstPart = ByteArray(420_000) { index -> ((index * 13) and 0xFF).toByte() }
        val secondPart = ByteArray(15_000) { index -> ((index xor 0x5A) and 0xFF).toByte() }
        val thirdPart = ByteArray(610_000) { index -> ((index * 29 + 7) and 0xFF).toByte() }
        val expectedConcatenated = firstPart + secondPart + thirdPart

        Arena.ofConfined().use { arena ->
            val mixedSlices = listOf(
                MemorySegment.ofArray(firstPart),
                arena.allocateFrom(ValueLayout.JAVA_BYTE, *secondPart),
                MemorySegment.ofArray(ByteArray(0)),
                MemorySegment.ofArray(thirdPart),
            )

            val compressed = gzip.compress(mixedSlices, arena)
            val decompressed = gzip.decompress(compressed, arena)
            assertEquals(expectedConcatenated.size.toLong(), decompressed.byteSize())
            assertTrue(expectedConcatenated.contentEquals(decompressed.toArray(ValueLayout.JAVA_BYTE)))

            val transientDecompressed = gzip.decompressTransient(compressed)
            assertEquals(expectedConcatenated.size.toLong(), transientDecompressed.byteSize())
            assertTrue(expectedConcatenated.contentEquals(transientDecompressed.toArray(ValueLayout.JAVA_BYTE)))
        }
    }

    @Test
    fun testParallelVarIntAndShortViewOperationsAboveThreshold() {
        val elementCount = 200_003
        val initialValues = ShortArray(elementCount) { index ->
            when (index % 3) {
                0 -> (index % 128).toShort()
                1 -> (128 + (index % 16_000)).toShort()
                else -> (20_000 + (index % 40_000)).toShort()
            }
        }

        Arena.ofConfined().use { arena ->
            val originalView = NativeShortView.of(initialValues, arena)
            val encodedTag = originalView.toVarIntByteArray(arena)
            val decodedView = encodedTag.toVarIntShortArray(elementCount, arena)

            for (sampleIndex in intArrayOf(0, 1, 2, 65_535, 65_536, 131_071, elementCount - 1)) {
                assertEquals(initialValues[sampleIndex], decodedView[sampleIndex])
            }

            decodedView.replaceAll(0.toShort(), 777.toShort())
            assertEquals(777.toShort(), decodedView[0])

            val largeTable = ShortArray(500) { index -> (index + 10).toShort() }
            decodedView.translate(largeTable)

            // shrink lookup table on the same thread to verify stale entries above 10 are cleared
            decodedView.fill(250.toShort())
            val smallTable = ShortArray(10) { index -> (index + 1).toShort() }
            decodedView.translate(smallTable)
            assertEquals(0.toShort(), decodedView[0])
            assertEquals(0.toShort(), decodedView[elementCount - 1])
        }
    }

}
