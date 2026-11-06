package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.ffi.TotemCodecSys
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference

internal class AnvilStagingBatch(
    private val compressionLevel: Int,
) {

    private val descriptorSegment: MemorySegment =
        Arena.ofAuto().allocate(ValueLayout.JAVA_LONG.byteSize() * 2L * MAX_STAGED_CHUNKS, ALIGNMENT_BYTES)

    private val sectorCountSegment: MemorySegment =
        Arena.ofAuto().allocate(ValueLayout.JAVA_INT.byteSize() * MAX_STAGED_CHUNKS, ALIGNMENT_BYTES)

    private val chunkIndices: IntArray = IntArray(MAX_STAGED_CHUNKS)
    private val slotByChunkIndex: IntArray = IntArray(AnvilSectorTable.CHUNK_COUNT) { NO_SLOT }

    private var rawBuffer: PooledRegionBuffer? = null
    private var slotsBuffer: PooledRegionBuffer? = null
    private var compactedBytes: Long = NOT_COMPRESSED
    private var stagedBytes: Long = 0L
    private var stagedCount: Int = 0

    val isEmpty: Boolean
        get() = stagedCount == 0

    fun contains(chunkIndex: Int): Boolean = slotByChunkIndex[chunkIndex] != NO_SLOT

    fun canAccept(rawByteSize: Long): Boolean {
        if (isEmpty) {
            return true
        }

        val capacity = rawBuffer?.capacityBytes ?: STAGING_BUFFER_BYTES
        return stagedCount < MAX_STAGED_CHUNKS && alignUp(stagedBytes) + rawByteSize <= capacity
    }

    fun stage(
        chunkIndex: Int,
        rawNbt: MemorySegment,
    ) {
        val rawByteSize = rawNbt.byteSize()
        require(rawByteSize >= 3L) {
            "Uncompressed chunk NBT size ($rawByteSize bytes) must be at least 3 bytes"
        }
        check(canAccept(rawByteSize)) {
            "Staging buffer cannot accept $rawByteSize more bytes after $stagedCount chunks; drain it first"
        }

        val stagedOffset = alignUp(stagedBytes)
        val buffer = ensureRawCapacity(stagedOffset + rawByteSize)
        MemorySegment.copy(rawNbt, 0L, buffer.segment, stagedOffset, rawByteSize)

        // overwriting an already-staged chunk updates its descriptor in place so superseded payloads are never encoded
        val existingSlot = slotByChunkIndex[chunkIndex]
        val targetSlot = when {
            existingSlot != NO_SLOT -> existingSlot
            else -> {
                val allocatedSlot = stagedCount
                chunkIndices[allocatedSlot] = chunkIndex
                slotByChunkIndex[chunkIndex] = allocatedSlot
                stagedCount += 1
                allocatedSlot
            }
        }

        val descriptorOffset = targetSlot * 2L * ValueLayout.JAVA_LONG.byteSize()
        descriptorSegment.set(ValueLayout.JAVA_LONG, descriptorOffset, buffer.segment.address() + stagedOffset)
        descriptorSegment.set(ValueLayout.JAVA_LONG, descriptorOffset + ValueLayout.JAVA_LONG.byteSize(), rawByteSize)

        stagedBytes = stagedOffset + rawByteSize
    }

    // touches only this batch's own off-heap buffers, so it may run on a background encoder thread
    fun compress() {
        if (isEmpty || compactedBytes != NOT_COMPRESSED) {
            return
        }

        var activeSlots = AnvilRegionBufferPool.acquire(estimateSlotBytes())
        slotsBuffer = activeSlots
        var framedBytes = frameInto(activeSlots)

        if (framedBytes < 0L && framedBytes != TotemCodecSys.STATUS_INVALID_ARGUMENT) {
            slotsBuffer = null
            AnvilRegionBufferPool.release(activeSlots)
            activeSlots = AnvilRegionBufferPool.acquire(-framedBytes)
            slotsBuffer = activeSlots
            framedBytes = frameInto(activeSlots)
        }

        compactedBytes = framedBytes
    }

    fun commitTo(commit: (chunkIndex: Int, framedSectors: MemorySegment) -> Unit) {
        if (isEmpty) {
            return
        }

        val oversizedChunks = ArrayList<Int>()

        try {
            compress()
            val framedSegment = checkNotNull(slotsBuffer) { "Staging slots buffer missing after compression." }.segment
            check(compactedBytes >= 0L) {
                "Native parallel chunk framing of $stagedCount staged chunks failed with status $compactedBytes"
            }

            var frameOffset = 0L
            for (slot in 0 until stagedCount) {
                val sectorCount = sectorCountSegment.getAtIndex(ValueLayout.JAVA_INT, slot.toLong())
                if (sectorCount <= 0) {
                    oversizedChunks.add(chunkIndices[slot])
                    continue
                }

                val frameBytes = sectorCount.toLong() * AnvilSectorTable.SECTOR_SIZE
                commit(chunkIndices[slot], framedSegment.asSlice(frameOffset, frameBytes))
                frameOffset += frameBytes
            }
        } finally {
            discard()
        }

        require(oversizedChunks.isEmpty()) {
            val coordinates = oversizedChunks.joinToString { chunkIndex -> "(${chunkIndex and 31}, ${chunkIndex ushr 5})" }
            "Chunks $coordinates compress beyond the Anvil limit of ${AnvilChunkEncodeScratch.MAX_CHUNK_SECTORS} sectors and were not written."
        }
    }

    fun discard() {
        for (slot in 0 until stagedCount) {
            slotByChunkIndex[chunkIndices[slot]] = NO_SLOT
        }

        slotsBuffer?.let { buffer -> AnvilRegionBufferPool.release(buffer) }
        slotsBuffer = null
        compactedBytes = NOT_COMPRESSED
        stagedCount = 0
        stagedBytes = 0L
    }

    fun release() {
        discard()
        rawBuffer?.let { buffer -> AnvilRegionBufferPool.release(buffer) }
        rawBuffer = null
    }

    private fun frameInto(slots: PooledRegionBuffer): Long = try {
        TotemCodecSys.frameChunks(
            compressionLevel,
            descriptorSegment,
            stagedCount,
            slots.segment,
            sectorCountSegment,
            parallel = true,
        )
    } finally {
        Reference.reachabilityFence(rawBuffer)
        Reference.reachabilityFence(slots)
        Reference.reachabilityFence(descriptorSegment)
        Reference.reachabilityFence(sectorCountSegment)
    }

    private fun ensureRawCapacity(requiredBytes: Long): PooledRegionBuffer {
        val currentBuffer = rawBuffer
        if (currentBuffer != null && requiredBytes <= currentBuffer.capacityBytes) {
            return currentBuffer
        }

        // only reachable while empty: canAccept() guarantees staged chunks always fit the current buffer
        currentBuffer?.let { undersizedBuffer -> AnvilRegionBufferPool.release(undersizedBuffer) }

        return AnvilRegionBufferPool.acquire(maxOf(STAGING_BUFFER_BYTES, requiredBytes)).also { acquiredBuffer ->
            rawBuffer = acquiredBuffer
        }
    }

    private fun estimateSlotBytes(): Long =
        stagedBytes + stagedBytes / 512L + stagedCount * 2L * AnvilSectorTable.SECTOR_SIZE

    private fun alignUp(offset: Long): Long = (offset + ALIGNMENT_BYTES - 1L) and (ALIGNMENT_BYTES - 1L).inv()

    companion object {

        const val MAX_STAGED_CHUNKS: Int = 1024
        const val STAGING_BUFFER_BYTES: Long = 16L * 1024L * 1024L

        private const val ALIGNMENT_BYTES: Long = 64L
        private const val NO_SLOT: Int = -1
        private const val NOT_COMPRESSED: Long = Long.MIN_VALUE

    }

}
