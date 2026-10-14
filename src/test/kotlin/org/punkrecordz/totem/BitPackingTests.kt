package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.codec.BlockDataCodec
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.view.toBitPackedLongArray
import org.punkrecordz.totem.view.toBitPackedShortArray
import java.lang.foreign.Arena
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitPackingTests {

    @Test
    fun testBlockDataCodecAndBitPackedViewRoundtrip() {
        val random = Random(999)

        Arena.ofConfined().use { arena ->
            for (bitsPerEntry in 1..16) {
                val maxEntryValue = if (bitsPerEntry == 16) 65536 else (1 shl bitsPerEntry)

                for (precise in booleanArrayOf(false, true)) {
                    val count = 4096
                    val longEntries = LongArray(count) { random.nextInt(maxEntryValue).toLong() }
                    val shortEntries = ShortArray(count) { index -> longEntries[index].toShort() }

                    val expectedPackedLongs = kotlinPackReference(longEntries, bitsPerEntry, precise)
                    val packedLongs = BlockDataCodec.pack(longEntries, bitsPerEntry, precise)
                    assertTrue(
                        expectedPackedLongs.contentEquals(packedLongs),
                        "Kotlin reference vs Rust pack mismatch for bits=$bitsPerEntry, precise=$precise",
                    )

                    val unpackedLongs = BlockDataCodec.unpack(packedLongs, bitsPerEntry, count, precise)
                    assertTrue(
                        longEntries.contentEquals(unpackedLongs),
                        "Long pack/unpack mismatch for bits=$bitsPerEntry, precise=$precise",
                    )

                    val packedTag = BlockDataCodec.packToTag(longEntries, bitsPerEntry, arena, precise)
                    val unpackedFromTag = BlockDataCodec.unpack(packedTag, bitsPerEntry, count, precise)
                    assertTrue(
                        longEntries.contentEquals(unpackedFromTag),
                        "NativeLongArrayTag unpack mismatch for bits=$bitsPerEntry, precise=$precise",
                    )

                    val packedShorts = BlockDataCodec.packShorts(shortEntries, bitsPerEntry, precise)
                    assertTrue(
                        packedLongs.contentEquals(packedShorts),
                        "Packed shorts vs longs mismatch for bits=$bitsPerEntry, precise=$precise",
                    )

                    val unpackedShorts = BlockDataCodec.unpackShorts(packedTag, bitsPerEntry, count, precise)
                    assertTrue(
                        shortEntries.contentEquals(unpackedShorts),
                        "Unpacked shorts mismatch for bits=$bitsPerEntry, precise=$precise",
                    )

                    val nativeShortView = NativeShortView.of(shortEntries, arena)
                    val bitPackedLongTag = nativeShortView.toBitPackedLongArray(bitsPerEntry, arena, precise)
                    val decodedShortView = bitPackedLongTag.toBitPackedShortArray(count, bitsPerEntry, arena, precise)

                    for (index in 0 until count) {
                        assertEquals(
                            shortEntries[index],
                            decodedShortView[index],
                            "Off-heap bit-packed ShortView mismatch at index $index for bits=$bitsPerEntry, precise=$precise",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testParallelBitPackedShortArrayRoundtrip() {
        val parallelCount = 300_000

        Arena.ofConfined().use { arena ->
            for (precise in booleanArrayOf(false, true)) {
                val parallelShorts = ShortArray(parallelCount) { index -> (index % 31).toShort() }
                val nativeShortView = NativeShortView.of(parallelShorts, arena)
                val packedTag = nativeShortView.toBitPackedLongArray(5, arena, precise)
                val unpackedView = packedTag.toBitPackedShortArray(parallelCount, 5, arena, precise)

                for (index in 0 until parallelCount) {
                    assertEquals(
                        parallelShorts[index],
                        unpackedView[index],
                        "Parallel bit-packed ShortView mismatch at index $index for precise=$precise",
                    )
                }
            }
        }
    }

    private fun kotlinPackReference(
        entries: LongArray,
        bitsPerEntry: Int,
        precise: Boolean,
    ): LongArray {
        if (precise) {
            val totalBits = entries.size * bitsPerEntry
            val longsCount = (totalBits + 63) ushr 6
            val packedLongs = LongArray(longsCount)
            val mask = (1L shl bitsPerEntry) - 1L
            var bitIndex = 0

            for (entry in entries) {
                val maskedValue = entry and mask
                val longIndex = bitIndex ushr 6
                val bitOffset = bitIndex and 63

                if (bitOffset + bitsPerEntry <= 64) {
                    packedLongs[longIndex] = packedLongs[longIndex] or (maskedValue shl bitOffset)
                } else {
                    val bitsInFirst = 64 - bitOffset
                    packedLongs[longIndex] = packedLongs[longIndex] or
                        ((maskedValue and ((1L shl bitsInFirst) - 1L)) shl bitOffset)
                    packedLongs[longIndex + 1] = packedLongs[longIndex + 1] or (maskedValue ushr bitsInFirst)
                }

                bitIndex += bitsPerEntry
            }

            return packedLongs
        }

        val entriesPerLong = 64 / bitsPerEntry
        val longsCount = (entries.size + entriesPerLong - 1) / entriesPerLong
        val packedLongs = LongArray(longsCount)
        val mask = (1L shl bitsPerEntry) - 1L
        var currentEntryIndex = 0

        for (longIndex in packedLongs.indices) {
            var wordValue = 0L
            var bitOffset = 0

            for (slotIndex in 0 until entriesPerLong) {
                if (currentEntryIndex >= entries.size) break
                wordValue = wordValue or ((entries[currentEntryIndex] and mask) shl bitOffset)
                bitOffset += bitsPerEntry
                currentEntryIndex++
            }

            packedLongs[longIndex] = wordValue
        }

        return packedLongs
    }

}
