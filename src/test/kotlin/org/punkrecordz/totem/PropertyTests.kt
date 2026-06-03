package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.impl.heap.HeapByteArrayTag
import org.punkrecordz.totem.impl.heap.HeapIntArrayTag
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.view.ArrayView
import org.punkrecordz.totem.view.contentEquals
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTests {

    @Test
    fun testViewConsistency() {
        val bytes = byteArrayOf(1, 2, 3, 2, 1)
        val heapView = HeapByteArrayTag(bytes)
        val indexOf1 = (0 until heapView.size).firstOrNull { heapView[it] == 1.toByte() } ?: -1
        val indexOf2 = (0 until heapView.size).firstOrNull { heapView[it] == 2.toByte() } ?: -1
        val indexOf9 = (0 until heapView.size).firstOrNull { heapView[it] == 9.toByte() } ?: -1
        assertEquals(0, indexOf1)
        assertEquals(1, indexOf2)
        assertEquals(-1, indexOf9)


        assertTrue(bytes.contentEquals(heapView.array))

        val copyView = heapView.copy()
        assertTrue(heapView.contentEquals(copyView))

        val otherView = HeapByteArrayTag(byteArrayOf(1, 2, 3, 2, 1))
        assertTrue(heapView.contentEquals(otherView))

        val mismatchedView = HeapByteArrayTag(byteArrayOf(1, 2, 3, 2, 9))
        kotlin.test.assertFalse(heapView.contentEquals(mismatchedView))

        // test generic ArrayView.contentEquals
        val arrayView1: ArrayView = heapView
        val arrayView2: ArrayView = otherView
        assertTrue(arrayView1.contentEquals(arrayView2))

        val diffTypeView: ArrayView = HeapIntArrayTag(intArrayOf(1, 2, 3, 2, 1))
        kotlin.test.assertFalse(arrayView1.contentEquals(diffTypeView))

        // test Native & Mixed contentEquals pathways
        Arena.ofConfined().use { arena ->
            val segment1 = arena.allocate(9) // 4 bytes for size (5) + 5 bytes for data
            segment1.set(MemoryLayouts.INT, 0L, 5)
            MemorySegment.copy(MemorySegment.ofArray(byteArrayOf(1, 2, 3, 2, 1)), 0L, segment1, 4L, 5L)
            val nativeView1 = NativeByteArrayTag(segment1)

            val segment2 = arena.allocate(9)
            segment2.set(MemoryLayouts.INT, 0L, 5)
            MemorySegment.copy(MemorySegment.ofArray(byteArrayOf(1, 2, 3, 2, 1)), 0L, segment2, 4L, 5L)
            val nativeView2 = NativeByteArrayTag(segment2)

            val segment3 = arena.allocate(9)
            segment3.set(MemoryLayouts.INT, 0L, 5)
            MemorySegment.copy(MemorySegment.ofArray(byteArrayOf(1, 2, 3, 2, 9)), 0L, segment3, 4L, 5L)
            val nativeView3 = NativeByteArrayTag(segment3)

            assertTrue(nativeView1.contentEquals(nativeView2))
            kotlin.test.assertFalse(nativeView1.contentEquals(nativeView3))

            // mixed comparison (heap vs native)
            assertTrue(heapView.contentEquals(nativeView1))
            assertTrue(nativeView1.contentEquals(heapView))
            kotlin.test.assertFalse(heapView.contentEquals(nativeView3))
        }
    }

    @Test
    fun testVarIntTranslationProperties() {
        val random = Random(42)

        Arena.ofConfined().use { arena ->
            repeat(1000) { iteration ->
                val size = random.nextInt(1000) + 1
                val array = ShortArray(size) {
                    when (random.nextInt(3)) {
                        0 -> 0.toShort()
                        1 -> (random.nextInt(128)).toShort()
                        else -> (random.nextInt(32768)).toShort()
                    }
                }

                val view = NativeShortView.of(array, arena)
                val encodedTag = view.toVarIntByteArray(arena)
                val decodedView = encodedTag.toVarIntShortArray(array.size, arena)

                val heapEncodedBytes = (encodedTag.pin() as HeapByteArrayTag).array
                val expectedEncodedBytes = array.toVarIntBytes()

                assertTrue(
                    heapEncodedBytes.contentEquals(expectedEncodedBytes),
                    "Encoded bytes mismatch at iteration $iteration.",
                )

                val heapDecodedArray = heapEncodedBytes.toVarInt(array.size)

                assertTrue(
                    array.contentEquals(heapDecodedArray),
                    "Classical heap decoded array mismatch at iteration $iteration.",
                )

                for (i in array.indices) {
                    assertEquals(
                        array[i],
                        decodedView[i],
                        "Off-heap decoded array mismatch at index $i in iteration $iteration.",
                    )
                }
            }
        }
    }

    private fun ByteArray.toVarInt(volume: Int): ShortArray {
        val destination = ShortArray(volume)
        var sourceIndex = 0
        var destinationIndex = 0

        while (sourceIndex < this.size && destinationIndex < volume) {
            var value = 0
            var shift = 0
            var byteValue: Int

            do {
                byteValue = this[sourceIndex++].toInt()
                value = value or ((byteValue and 0x7F) shl shift)
                shift += 7
            } while (byteValue and 0x80 != 0)

            destination[destinationIndex++] = value.toShort()
        }

        return destination
    }

    private fun ShortArray.toVarIntBytes(): ByteArray {
        var size = 0

        for (value in this) {
            var elementValue = value.toInt() and 0xFFFF

            do {
                size++
                elementValue = elementValue ushr 7
            } while (elementValue != 0)
        }

        val bytes = ByteArray(size)
        var byteIndex = 0

        for (value in this) {
            var elementValue = value.toInt() and 0xFFFF

            do {
                var temporaryValue = elementValue and 0x7F
                elementValue = elementValue ushr 7

                if (elementValue != 0) {
                    temporaryValue = temporaryValue or 0x80
                }

                bytes[byteIndex++] = temporaryValue.toByte()
            } while (elementValue != 0)
        }

        return bytes
    }

}
