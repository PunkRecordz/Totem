package org.punkrecordz.totem.codec

internal object SectionShortBitPacker {

    const val SECTION_ELEMENT_COUNT: Int = 4096

    fun tryPackSectionShorts(
        entries: ShortArray,
        bitsPerEntry: Int,
        precise: Boolean,
    ): LongArray? {
        if (entries.size != SECTION_ELEMENT_COUNT) {
            return null
        }

        return when {
            bitsPerEntry == 4 -> packSection4Bits(entries)
            bitsPerEntry == 8 -> packSection8Bits(entries)
            !precise && bitsPerEntry == 5 -> packSection5BitsPadded(entries)
            !precise && bitsPerEntry == 6 -> packSection6BitsPadded(entries)
            !precise && bitsPerEntry == 7 -> packSection7BitsPadded(entries)
            !precise || (64 % bitsPerEntry == 0) -> packSectionGenericPadded(entries, bitsPerEntry)
            else -> null
        }
    }

    private fun packSection4Bits(entries: ShortArray): LongArray {
        val packed = LongArray(256)
        var baseIndex = 0

        for (wordIndex in 0 until 256) {
            packed[wordIndex] =
                (entries[baseIndex].toLong() and 0xFL) or
                ((entries[baseIndex + 1].toLong() and 0xFL) shl 4) or
                ((entries[baseIndex + 2].toLong() and 0xFL) shl 8) or
                ((entries[baseIndex + 3].toLong() and 0xFL) shl 12) or
                ((entries[baseIndex + 4].toLong() and 0xFL) shl 16) or
                ((entries[baseIndex + 5].toLong() and 0xFL) shl 20) or
                ((entries[baseIndex + 6].toLong() and 0xFL) shl 24) or
                ((entries[baseIndex + 7].toLong() and 0xFL) shl 28) or
                ((entries[baseIndex + 8].toLong() and 0xFL) shl 32) or
                ((entries[baseIndex + 9].toLong() and 0xFL) shl 36) or
                ((entries[baseIndex + 10].toLong() and 0xFL) shl 40) or
                ((entries[baseIndex + 11].toLong() and 0xFL) shl 44) or
                ((entries[baseIndex + 12].toLong() and 0xFL) shl 48) or
                ((entries[baseIndex + 13].toLong() and 0xFL) shl 52) or
                ((entries[baseIndex + 14].toLong() and 0xFL) shl 56) or
                ((entries[baseIndex + 15].toLong() and 0xFL) shl 60)
            baseIndex += 16
        }

        return packed
    }

    private fun packSection5BitsPadded(entries: ShortArray): LongArray {
        val packed = LongArray(342)
        var baseIndex = 0

        for (wordIndex in 0 until 341) {
            packed[wordIndex] =
                (entries[baseIndex].toLong() and 0x1FL) or
                ((entries[baseIndex + 1].toLong() and 0x1FL) shl 5) or
                ((entries[baseIndex + 2].toLong() and 0x1FL) shl 10) or
                ((entries[baseIndex + 3].toLong() and 0x1FL) shl 15) or
                ((entries[baseIndex + 4].toLong() and 0x1FL) shl 20) or
                ((entries[baseIndex + 5].toLong() and 0x1FL) shl 25) or
                ((entries[baseIndex + 6].toLong() and 0x1FL) shl 30) or
                ((entries[baseIndex + 7].toLong() and 0x1FL) shl 35) or
                ((entries[baseIndex + 8].toLong() and 0x1FL) shl 40) or
                ((entries[baseIndex + 9].toLong() and 0x1FL) shl 45) or
                ((entries[baseIndex + 10].toLong() and 0x1FL) shl 50) or
                ((entries[baseIndex + 11].toLong() and 0x1FL) shl 55)
            baseIndex += 12
        }

        packed[341] =
            (entries[4092].toLong() and 0x1FL) or
            ((entries[4093].toLong() and 0x1FL) shl 5) or
            ((entries[4094].toLong() and 0x1FL) shl 10) or
            ((entries[4095].toLong() and 0x1FL) shl 15)

        return packed
    }

    private fun packSection6BitsPadded(entries: ShortArray): LongArray {
        val packed = LongArray(410)
        var baseIndex = 0

        for (wordIndex in 0 until 409) {
            packed[wordIndex] =
                (entries[baseIndex].toLong() and 0x3FL) or
                ((entries[baseIndex + 1].toLong() and 0x3FL) shl 6) or
                ((entries[baseIndex + 2].toLong() and 0x3FL) shl 12) or
                ((entries[baseIndex + 3].toLong() and 0x3FL) shl 18) or
                ((entries[baseIndex + 4].toLong() and 0x3FL) shl 24) or
                ((entries[baseIndex + 5].toLong() and 0x3FL) shl 30) or
                ((entries[baseIndex + 6].toLong() and 0x3FL) shl 36) or
                ((entries[baseIndex + 7].toLong() and 0x3FL) shl 42) or
                ((entries[baseIndex + 8].toLong() and 0x3FL) shl 48) or
                ((entries[baseIndex + 9].toLong() and 0x3FL) shl 54)
            baseIndex += 10
        }

        packed[409] =
            (entries[4090].toLong() and 0x3FL) or
            ((entries[4091].toLong() and 0x3FL) shl 6) or
            ((entries[4092].toLong() and 0x3FL) shl 12) or
            ((entries[4093].toLong() and 0x3FL) shl 18) or
            ((entries[4094].toLong() and 0x3FL) shl 24) or
            ((entries[4095].toLong() and 0x3FL) shl 30)

        return packed
    }

    private fun packSection7BitsPadded(entries: ShortArray): LongArray {
        val packed = LongArray(456)
        var baseIndex = 0

        for (wordIndex in 0 until 455) {
            packed[wordIndex] =
                (entries[baseIndex].toLong() and 0x7FL) or
                ((entries[baseIndex + 1].toLong() and 0x7FL) shl 7) or
                ((entries[baseIndex + 2].toLong() and 0x7FL) shl 14) or
                ((entries[baseIndex + 3].toLong() and 0x7FL) shl 21) or
                ((entries[baseIndex + 4].toLong() and 0x7FL) shl 28) or
                ((entries[baseIndex + 5].toLong() and 0x7FL) shl 35) or
                ((entries[baseIndex + 6].toLong() and 0x7FL) shl 42) or
                ((entries[baseIndex + 7].toLong() and 0x7FL) shl 49) or
                ((entries[baseIndex + 8].toLong() and 0x7FL) shl 56)
            baseIndex += 9
        }

        packed[455] = entries[4095].toLong() and 0x7FL

        return packed
    }

    private fun packSection8Bits(entries: ShortArray): LongArray {
        val packed = LongArray(512)
        var baseIndex = 0

        for (wordIndex in 0 until 512) {
            packed[wordIndex] =
                (entries[baseIndex].toLong() and 0xFFL) or
                ((entries[baseIndex + 1].toLong() and 0xFFL) shl 8) or
                ((entries[baseIndex + 2].toLong() and 0xFFL) shl 16) or
                ((entries[baseIndex + 3].toLong() and 0xFFL) shl 24) or
                ((entries[baseIndex + 4].toLong() and 0xFFL) shl 32) or
                ((entries[baseIndex + 5].toLong() and 0xFFL) shl 40) or
                ((entries[baseIndex + 6].toLong() and 0xFFL) shl 48) or
                ((entries[baseIndex + 7].toLong() and 0xFFL) shl 56)
            baseIndex += 8
        }

        return packed
    }

    private fun packSectionGenericPadded(
        entries: ShortArray,
        bitsPerEntry: Int,
    ): LongArray {
        val entriesPerLong = 64 / bitsPerEntry
        val longsCount = (SECTION_ELEMENT_COUNT + entriesPerLong - 1) / entriesPerLong
        val packed = LongArray(longsCount)
        val mask = (1L shl bitsPerEntry) - 1L
        var entryIndex = 0

        for (wordIndex in 0 until longsCount) {
            var wordValue = 0L
            var bitOffset = 0

            for (slotIndex in 0 until entriesPerLong) {
                if (entryIndex >= SECTION_ELEMENT_COUNT) {
                    break
                }
                wordValue = wordValue or ((entries[entryIndex].toLong() and mask) shl bitOffset)
                bitOffset += bitsPerEntry
                entryIndex++
            }

            packed[wordIndex] = wordValue
        }

        return packed
    }

}
