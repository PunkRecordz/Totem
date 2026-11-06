package org.punkrecordz.totem.anvil

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.BitSet

internal class AnvilSectorTable {

    val offsets: IntArray = IntArray(CHUNK_COUNT)
    val timestamps: IntArray = IntArray(CHUNK_COUNT)

    private val usedSectors: BitSet = BitSet().also { initialSectors ->
        initialSectors.set(0, HEADER_SECTORS)
    }

    private var firstFreeSectorHint: Int = HEADER_SECTORS

    fun loadFromSegment(segment: MemorySegment?) {
        if (segment == null || segment.byteSize() < HEADER_SIZE.toLong()) {
            return
        }

        for (index in 0 until CHUNK_COUNT) {
            offsets[index] = segment.get(BIG_ENDIAN_INT, index * 4L)
            timestamps[index] = segment.get(BIG_ENDIAN_INT, SECTOR_SIZE + index * 4L)
        }

        recalculateUsedSectors()
    }

    fun writeHeadersToSegment(segment: MemorySegment) {
        for (index in 0 until CHUNK_COUNT) {
            segment.set(BIG_ENDIAN_INT, index * 4L, offsets[index])
            segment.set(BIG_ENDIAN_INT, SECTOR_SIZE + index * 4L, timestamps[index])
        }
    }

    fun listPresentChunks(): List<ChunkCoordinate> {
        val chunks = ArrayList<ChunkCoordinate>()
        offsets.forEachIndexed { index, entry ->
            if (entry != 0) {
                chunks.add(
                    ChunkCoordinate(
                        x = index and 31,
                        z = index ushr 5,
                    ),
                )
            }
        }

        return chunks
    }

    fun hasAnyChunk(
        firstChunkIndex: Int,
        chunkCount: Int,
    ): Boolean = (firstChunkIndex until minOf(CHUNK_COUNT, firstChunkIndex + chunkCount)).any { chunkIndex ->
        offsets[chunkIndex] != 0
    }

    fun setChunkEntrySequential(
        chunkIndex: Int,
        sectorOffset: Int,
        requiredSectors: Int,
    ) {
        check(sectorOffset in HEADER_SECTORS..MAX_SECTOR_OFFSET) {
            "Sequential sector offset $sectorOffset is out of valid Anvil range"
        }

        offsets[chunkIndex] = (sectorOffset shl 8) or requiredSectors
        timestamps[chunkIndex] = currentEpochSeconds()
    }

    fun allocateChunkSectors(
        chunkIndex: Int,
        requiredSectors: Int,
    ): Int {
        val previousEntry = offsets[chunkIndex]
        if (previousEntry != 0) {
            val previousOffset = previousEntry ushr 8
            val previousCount = previousEntry and 0xFF
            if (previousOffset >= HEADER_SECTORS && previousCount > 0) {
                usedSectors.clear(previousOffset, previousOffset + previousCount)
                if (previousOffset < firstFreeSectorHint) {
                    firstFreeSectorHint = previousOffset
                }
            }
        }

        val newSectorOffset = findFreeSectors(requiredSectors)
        check(newSectorOffset <= MAX_SECTOR_OFFSET) {
            "Allocated sector offset $newSectorOffset exceeds 24-bit Anvil sector limit"
        }

        offsets[chunkIndex] = (newSectorOffset shl 8) or requiredSectors
        timestamps[chunkIndex] = currentEpochSeconds()
        usedSectors.set(newSectorOffset, newSectorOffset + requiredSectors)

        if (newSectorOffset == firstFreeSectorHint) {
            firstFreeSectorHint = usedSectors.nextClearBit(newSectorOffset + requiredSectors)
        }

        return newSectorOffset
    }

    private fun findFreeSectors(required: Int): Int {
        var startCheck = maxOf(HEADER_SECTORS, firstFreeSectorHint)
        var recordedFirstFree = false

        while (true) {
            val nextClear = usedSectors.nextClearBit(startCheck)
            if (!recordedFirstFree) {
                firstFreeSectorHint = nextClear
                recordedFirstFree = true
            }

            val nextSet = usedSectors.nextSetBit(nextClear)
            if (nextSet == -1 || nextSet - nextClear >= required) {
                return nextClear
            }

            startCheck = nextSet
        }
    }

    private fun recalculateUsedSectors() {
        usedSectors.clear()
        usedSectors.set(0, HEADER_SECTORS)

        for (entry in offsets) {
            if (entry != 0) {
                val startSector = entry ushr 8
                val sectorCount = entry and 0xFF
                if (startSector >= HEADER_SECTORS && sectorCount > 0) {
                    usedSectors.set(startSector, startSector + sectorCount)
                }
            }
        }

        firstFreeSectorHint = usedSectors.nextClearBit(HEADER_SECTORS)
    }

    private fun currentEpochSeconds(): Int = (System.currentTimeMillis() / 1000L).toInt()

    companion object {

        const val SECTOR_SIZE: Int = 4096
        const val HEADER_SECTORS: Int = 2
        const val HEADER_SIZE: Int = SECTOR_SIZE * HEADER_SECTORS
        const val CHUNK_COUNT: Int = 1024
        const val COMPRESSION_GZIP: Int = CompressedChunkPayload.COMPRESSION_GZIP
        const val COMPRESSION_DEFLATE: Int = CompressedChunkPayload.COMPRESSION_ZLIB
        const val COMPRESSION_NONE: Int = CompressedChunkPayload.COMPRESSION_NONE

        private const val MAX_SECTOR_OFFSET: Int = 0xFFFFFF

        val BIG_ENDIAN_INT: ValueLayout.OfInt =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN)

        fun getChunkIndex(
            chunkX: Int,
            chunkZ: Int,
        ): Int {
            val localX = chunkX and 31
            val localZ = chunkZ and 31
            return localX or (localZ shl 5)
        }

    }

}
