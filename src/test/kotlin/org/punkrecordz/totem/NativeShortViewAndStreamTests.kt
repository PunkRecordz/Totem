package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.codec.IntArrayProtocol
import org.punkrecordz.totem.codec.LongArrayProtocol
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.GzipCompression
import org.punkrecordz.totem.io.stream.NativeByteArrayOutputStream
import org.punkrecordz.totem.tag.Tags
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeShortViewAndStreamTests {

    @Test
    fun testNativeShortViewReplaceAllAndFill() {
        Arena.ofConfined().use { arena ->
            val size = 100_000
            val view = NativeShortView.of(size, arena)

            view.fill(0.toShort())
            assertEquals(0.toShort(), view[0])
            assertEquals(0.toShort(), view[size - 1])

            view[10] = 5.toShort()
            view[500] = 5.toShort()
            view[99_999] = 5.toShort()

            view.replaceAll(5.toShort(), 42.toShort())
            assertEquals(42.toShort(), view[10])
            assertEquals(42.toShort(), view[500])
            assertEquals(42.toShort(), view[99_999])
            assertEquals(0.toShort(), view[0])

            view.fill(7.toShort())
            assertEquals(7.toShort(), view[0])
            assertEquals(7.toShort(), view[10])
            assertEquals(7.toShort(), view[size - 1])
        }
    }

    @Test
    fun testNativeShortViewTranslate() {
        Arena.ofConfined().use { arena ->
            val elementCount = 200_000
            val originalView = NativeShortView.of(elementCount, arena)

            val lookupTable = ShortArray(65536) { index -> index.toShort() }
            lookupTable[10] = 42.toShort()
            lookupTable[50] = 99.toShort()

            for (index in 0 until elementCount) {
                originalView[index] = (index % 100).toShort()
            }

            originalView.translate(lookupTable)

            for (index in 0 until elementCount) {
                val base = (index % 100).toShort()
                val expected = when (base) {
                    10.toShort() -> 42.toShort()
                    50.toShort() -> 99.toShort()
                    else -> base
                }

                assertEquals(
                    expected,
                    originalView[index],
                    "Translation mismatch at index $index",
                )
            }
        }
    }

    @Test
    fun testNativePartitionVarIntStream() {
        Arena.ofConfined().use { arena ->
            val elementCount = 150_000
            val originalView = NativeShortView.of(elementCount, arena)

            for (index in 0 until elementCount) {
                originalView[index] = when (index % 5) {
                    0 -> 0.toShort()
                    1 -> 127.toShort()
                    2 -> 128.toShort()
                    3 -> 16383.toShort()
                    else -> 20000.toShort()
                }
            }

            val encodedByteArrayTag = originalView.toVarIntByteArray(arena)
            val decodedView = encodedByteArrayTag.toVarIntShortArray(elementCount, arena)

            for (index in 0 until elementCount) {
                assertEquals(
                    originalView[index],
                    decodedView[index],
                    "Decoded mismatch at index $index",
                )
            }
        }
    }

    @Test
    fun testParallelMultiMemberGzipRoundtrip() {
        Arena.ofConfined().use { arena ->
            val payload = ByteArray(32 * 1024 * 1024) { index -> (index % 256).toByte() }
            val inputSegment = MemorySegment.ofArray(payload)
            val gzip = GzipCompression(1)

            val compressedSegment = gzip.compress(inputSegment, arena)
            val decompressedSegment = gzip.decompress(compressedSegment, arena)

            assertEquals(payload.size.toLong(), decompressedSegment.byteSize())
            val decompressedBytes = decompressedSegment.toArray(ValueLayout.JAVA_BYTE)
            assertTrue(payload.contentEquals(decompressedBytes))
        }
    }

    @Test
    fun testNativeByteArrayOutputStreamArrayCopies() {
        Arena.ofConfined().use { arena ->
            val outputStream = NativeByteArrayOutputStream(arena)
            val testInts = intArrayOf(0x12345678, 0x01020304, -1, 42)
            val testLongs = longArrayOf(0x123456789ABCDEF0L, 0x0102030405060708L, -1L, 42L)

            outputStream.writeIntArray(testInts)
            outputStream.writeLongArray(testLongs)

            val segment = outputStream.toContiguousSegment(arena)

            var offset = 0L
            for (index in testInts.indices) {
                val value = segment.get(MemoryLayouts.INT, offset)
                assertEquals(testInts[index], value)
                offset += 4L
            }

            for (index in testLongs.indices) {
                val value = segment.get(MemoryLayouts.LONG, offset)
                assertEquals(testLongs[index], value)
                offset += 8L
            }

            val protocolSegment = arena.allocateUninitialized(256)
            val heapIntTag = Tags.intArray(testInts)
            val writtenIntBytes = IntArrayProtocol.write(protocolSegment, 0L, heapIntTag)
            val readIntTag = IntArrayProtocol.read(protocolSegment, 0L)
            assertEquals(testInts.size, readIntTag.size)

            for (index in testInts.indices) {
                assertEquals(testInts[index], readIntTag[index])
            }

            val heapLongTag = Tags.longArray(testLongs)
            LongArrayProtocol.write(protocolSegment, writtenIntBytes, heapLongTag)
            val readLongTag = LongArrayProtocol.read(protocolSegment, writtenIntBytes)
            assertEquals(testLongs.size, readLongTag.size)

            for (index in testLongs.indices) {
                assertEquals(testLongs[index], readLongTag[index])
            }
        }
    }

    @Test
    fun testScanMatchingIndices() {
        Arena.ofConfined().use { arena ->
            val size = 1000
            val view = NativeShortView.of(size, arena)
            view.fill(0)
            view[10] = 5.toShort()
            view[50] = 42.toShort()
            view[200] = 5.toShort()
            view[800] = 123.toShort()

            val shouldIgnore = BooleanArray(65536) { true }
            shouldIgnore[5] = false
            shouldIgnore[42] = false
            shouldIgnore[123] = false

            val matches = view.scanMatchingIndices(shouldIgnore)
            assertEquals(4, matches.size)
            assertEquals(10, matches[0])
            assertEquals(50, matches[1])
            assertEquals(200, matches[2])
            assertEquals(800, matches[3])
        }
    }

}
