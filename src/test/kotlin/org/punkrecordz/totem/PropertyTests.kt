package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.impl.heap.HeapByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import java.lang.foreign.Arena
import java.util.Arrays
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTests {

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
                val decodedArray = decodedView.pin()

                val heapEncodedBytes = (encodedTag.pin() as HeapByteArrayTag).array
                val expectedEncodedBytes = array.toVarIntBytes()

                assertTrue(
                    Arrays.equals(heapEncodedBytes, expectedEncodedBytes),
                    "Encoded bytes mismatch at iteration $iteration.",
                )

                val heapDecodedArray = heapEncodedBytes.toVarInt(array.size)

                assertTrue(
                    Arrays.equals(array, heapDecodedArray),
                    "Classical heap decoded array mismatch at iteration $iteration.",
                )

                assertTrue(
                    Arrays.equals(array, decodedArray),
                    "Off-heap decoded array mismatch at iteration $iteration.",
                )
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
