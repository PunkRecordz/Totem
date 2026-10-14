package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.BitSet

internal class AnvilSectorTable {

    companion object {

        const val SECTOR_SIZE: Int = 4096
        const val HEADER_SECTORS: Int = 2
        const val HEADER_SIZE: Int = SECTOR_SIZE * HEADER_SECTORS
        const val CHUNK_COUNT: Int = 1024
        const val COMPRESSION_GZIP: Int = 1
        const val COMPRESSION_DEFLATE: Int = 2

        val BIG_ENDIAN_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN)

        fun getChunkIndex(chunkX: Int, chunkZ: Int): Int {
            return ((chunkX % 32 + 32) % 32) + ((chunkZ % 32 + 32) % 32) * 32
        }

    }

    val offsets: IntArray = IntArray(CHUNK_COUNT)
    val timestamps: IntArray = IntArray(CHUNK_COUNT)
    private val usedSectors: BitSet = BitSet()

    fun initializeEmptyHeader(channel: FileChannel) {
        Arena.ofConfined().use { tempArena ->
            val headerSegment = tempArena.allocateUninitialized(HEADER_SIZE.toLong())
            headerSegment.fill(0.toByte())
            channel.write(headerSegment.asByteBuffer(), 0L)
        }
        usedSectors.set(0, HEADER_SECTORS)
    }

    fun loadFromMapping(segment: MemorySegment?) {
        if (segment == null) return

        for (index in 0 until CHUNK_COUNT) {
            offsets[index] = segment.get(BIG_ENDIAN_INT, index * 4L)
            timestamps[index] = segment.get(BIG_ENDIAN_INT, SECTOR_SIZE + index * 4L)
        }
        recalculateUsedSectors()
    }

    fun loadFromChannel(channel: FileChannel) {
        if (channel.size() < HEADER_SIZE) return

        Arena.ofConfined().use { tempArena ->
            val headerSegment = tempArena.allocateUninitialized(HEADER_SIZE.toLong())
            channel.read(headerSegment.asByteBuffer(), 0L)
            loadFromMapping(headerSegment)
        }
    }

    fun listPresentChunks(): List<ChunkCoordinate> {
        val chunks = ArrayList<ChunkCoordinate>()
        offsets.forEachIndexed { index, entry ->
            if (entry != 0) {
                chunks.add(
                    ChunkCoordinate(
                        x = index % 32,
                        z = index / 32,
                    ),
                )
            }
        }

        return chunks
    }

    fun allocateChunkSectors(
        chunkIndex: Int,
        requiredSectors: Int,
    ): Int {
        val previousEntry = offsets[chunkIndex]
        if (previousEntry != 0) {
            val previousOffset = previousEntry ushr 8
            val previousCount = previousEntry and 0xFF
            usedSectors.clear(previousOffset, previousOffset + previousCount)
        }

        val newSectorOffset = findFreeSectors(requiredSectors)
        val newEntry = (newSectorOffset shl 8) or requiredSectors
        val newTimestamp = (System.currentTimeMillis() / 1000).toInt()

        offsets[chunkIndex] = newEntry
        timestamps[chunkIndex] = newTimestamp
        usedSectors.set(newSectorOffset, newSectorOffset + requiredSectors)

        return newSectorOffset
    }

    fun writeChunkHeaderEntry(
        channel: FileChannel,
        tempArena: Arena,
        chunkIndex: Int,
    ) {
        val headerSegment = tempArena.allocateUninitialized(8)
        headerSegment.set(BIG_ENDIAN_INT, 0L, offsets[chunkIndex])
        headerSegment.set(BIG_ENDIAN_INT, 4L, timestamps[chunkIndex])

        channel.write(headerSegment.asSlice(0, 4).asByteBuffer(), chunkIndex.toLong() * 4L)
        channel.write(
            headerSegment.asSlice(4, 4).asByteBuffer(),
            SECTOR_SIZE.toLong() + chunkIndex.toLong() * 4L,
        )
    }

    fun flushAllHeaders(channel: FileChannel) {
        Arena.ofConfined().use { tempArena ->
            val headerSegment = tempArena.allocateUninitialized(HEADER_SIZE.toLong())

            for (index in 0 until CHUNK_COUNT) {
                headerSegment.set(BIG_ENDIAN_INT, index * 4L, offsets[index])
                headerSegment.set(BIG_ENDIAN_INT, SECTOR_SIZE + index * 4L, timestamps[index])
            }

            channel.write(headerSegment.asByteBuffer(), 0L)
        }
    }

    private fun findFreeSectors(required: Int): Int {
        var startCheck = HEADER_SECTORS
        while (true) {
            val nextClear = usedSectors.nextClearBit(startCheck)
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
        offsets.forEach { entry ->
            if (entry != 0) {
                val start = entry ushr 8
                val count = entry and 0xFF
                usedSectors.set(start, start + count)
            }
        }
    }

}
