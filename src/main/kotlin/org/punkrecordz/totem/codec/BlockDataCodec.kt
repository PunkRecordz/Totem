package org.punkrecordz.totem.codec

import org.punkrecordz.totem.ffi.TotemBitPackSys
import org.punkrecordz.totem.impl.heap.HeapLongArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.LongArrayTag
import org.punkrecordz.totem.view.LongView
import java.lang.Integer.numberOfLeadingZeros
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.math.max

object BlockDataCodec {

    fun getBitsPerBlock(paletteSize: Int): Int = getBitsPerEntry(paletteSize, minBits = 4)

    fun getBitsPerBiome(paletteSize: Int): Int = getBitsPerEntry(paletteSize, minBits = 1)

    fun getBitsPerEntry(
        paletteSize: Int,
        minBits: Int = 4,
    ): Int {
        if (paletteSize <= 1) return minBits
        return max(minBits, 32 - numberOfLeadingZeros(paletteSize - 1))
    }

    fun calculatePackedLongCount(
        elementCount: Int,
        bitsPerEntry: Int,
        precise: Boolean = false,
    ): Int {
        require(elementCount >= 0) { "elementCount must be non-negative, but was $elementCount" }
        require(bitsPerEntry in 1..64) { "bitsPerEntry must be in 1..64, but was $bitsPerEntry" }

        if (elementCount == 0) return 0

        return if (precise) {
            val totalBits = elementCount.toLong() * bitsPerEntry
            ((totalBits + 63L) ushr 6).toInt()
        } else {
            val entriesPerLong = 64 / bitsPerEntry
            (elementCount + entriesPerLong - 1) / entriesPerLong
        }
    }

    fun pack(
        entries: LongArray,
        bitsPerEntry: Int,
        precise: Boolean = false,
    ): LongArray {
        val longsCount = calculatePackedLongCount(entries.size, bitsPerEntry, precise)
        if (longsCount == 0) return LongArray(0)

        val packed = LongArray(longsCount)
        TotemBitPackSys.packLongsToLongs(
            MemorySegment.ofArray(entries),
            entries.size.toLong(),
            MemorySegment.ofArray(packed),
            longsCount.toLong(),
            bitsPerEntry,
            precise,
            bigEndianDestination = false,
        )

        return packed
    }

    fun packToTag(
        entries: LongArray,
        bitsPerEntry: Int,
        arena: Arena,
        precise: Boolean = false,
    ): LongArrayTag {
        val longsCount = calculatePackedLongCount(entries.size, bitsPerEntry, precise)
        val headerSize = MemoryLayouts.INT.byteSize()
        val segment = arena.allocateUninitialized(headerSize + (longsCount.toLong() * Long.SIZE_BYTES))
        segment.set(MemoryLayouts.INT, 0L, longsCount)

        if (longsCount > 0) {
            TotemBitPackSys.packLongsToLongs(
                MemorySegment.ofArray(entries),
                entries.size.toLong(),
                segment.asSlice(headerSize),
                longsCount.toLong(),
                bitsPerEntry,
                precise,
                bigEndianDestination = true,
            )
        }

        return NativeLongArrayTag(segment)
    }

    fun unpack(
        longs: LongArray,
        bitsPerEntry: Int,
        count: Int,
        precise: Boolean = false,
    ): LongArray {
        require(count >= 0) { "count must be non-negative, but was $count" }
        require(bitsPerEntry in 1..64) { "bitsPerEntry must be in 1..64, but was $bitsPerEntry" }

        val unpackedSize = if (precise) count else minOf(longs.size * (64 / bitsPerEntry), count)
        if (unpackedSize <= 0) return LongArray(0)

        val unpacked = LongArray(unpackedSize)
        TotemBitPackSys.unpackLongsToLongs(
            MemorySegment.ofArray(longs),
            longs.size.toLong(),
            MemorySegment.ofArray(unpacked),
            unpackedSize.toLong(),
            bitsPerEntry,
            precise,
            bigEndianSource = false,
        )

        return unpacked
    }

    fun unpack(
        longs: LongView,
        bitsPerEntry: Int,
        count: Int,
        precise: Boolean = false,
    ): LongArray {
        if (longs is HeapLongArrayTag) {
            return unpack(longs.array, bitsPerEntry, count, precise)
        }

        if (longs is NativeLongArrayTag) {
            require(count >= 0) { "count must be non-negative, but was $count" }
            require(bitsPerEntry in 1..64) { "bitsPerEntry must be in 1..64, but was $bitsPerEntry" }

            val longsCount = longs.size
            val unpackedSize = if (precise) count else minOf(longsCount * (64 / bitsPerEntry), count)
            if (unpackedSize <= 0) return LongArray(0)

            val unpacked = LongArray(unpackedSize)
            TotemBitPackSys.unpackLongsToLongs(
                longs.segment.asSlice(MemoryLayouts.INT.byteSize()),
                longsCount.toLong(),
                MemorySegment.ofArray(unpacked),
                unpackedSize.toLong(),
                bitsPerEntry,
                precise,
                bigEndianSource = true,
            )

            return unpacked
        }

        val fallbackArray = LongArray(longs.size) { index -> longs[index] }
        return unpack(fallbackArray, bitsPerEntry, count, precise)
    }

    fun packShorts(
        entries: ShortArray,
        bitsPerEntry: Int,
        precise: Boolean = false,
    ): LongArray {
        require(bitsPerEntry in 1..16) { "bitsPerEntry for ShortArray must be in 1..16, but was $bitsPerEntry" }

        val longsCount = calculatePackedLongCount(entries.size, bitsPerEntry, precise)
        if (longsCount == 0) return LongArray(0)

        val packed = LongArray(longsCount)
        TotemBitPackSys.packShortsToLongs(
            MemorySegment.ofArray(entries),
            entries.size.toLong(),
            MemorySegment.ofArray(packed),
            longsCount.toLong(),
            bitsPerEntry,
            precise,
            bigEndianDestination = false,
        )

        return packed
    }

    fun unpackShorts(
        longs: LongArray,
        bitsPerEntry: Int,
        count: Int,
        precise: Boolean = false,
    ): ShortArray {
        require(count >= 0) { "count must be non-negative, but was $count" }
        require(bitsPerEntry in 1..16) { "bitsPerEntry for ShortArray must be in 1..16, but was $bitsPerEntry" }

        val unpackedSize = if (precise) count else minOf(longs.size * (64 / bitsPerEntry), count)
        if (unpackedSize <= 0) return ShortArray(0)

        val unpacked = ShortArray(unpackedSize)
        TotemBitPackSys.unpackLongsToShorts(
            MemorySegment.ofArray(longs),
            longs.size.toLong(),
            MemorySegment.ofArray(unpacked),
            unpackedSize.toLong(),
            bitsPerEntry,
            precise,
            bigEndianSource = false,
        )

        return unpacked
    }

    fun unpackShorts(
        longs: LongView,
        bitsPerEntry: Int,
        count: Int,
        precise: Boolean = false,
    ): ShortArray {
        if (longs is HeapLongArrayTag) {
            return unpackShorts(longs.array, bitsPerEntry, count, precise)
        }

        if (longs is NativeLongArrayTag) {
            require(count >= 0) { "count must be non-negative, but was $count" }
            require(bitsPerEntry in 1..16) { "bitsPerEntry for ShortArray must be in 1..16, but was $bitsPerEntry" }

            val longsCount = longs.size
            val unpackedSize = if (precise) count else minOf(longsCount * (64 / bitsPerEntry), count)
            if (unpackedSize <= 0) return ShortArray(0)

            val unpacked = ShortArray(unpackedSize)
            TotemBitPackSys.unpackLongsToShorts(
                longs.segment.asSlice(MemoryLayouts.INT.byteSize()),
                longsCount.toLong(),
                MemorySegment.ofArray(unpacked),
                unpackedSize.toLong(),
                bitsPerEntry,
                precise,
                bigEndianSource = true,
            )

            return unpacked
        }

        val fallbackArray = LongArray(longs.size) { index -> longs[index] }
        return unpackShorts(fallbackArray, bitsPerEntry, count, precise)
    }

}