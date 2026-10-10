package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeIntArrayTag
import org.punkrecordz.totem.impl.native.NativeLongArrayTag
import org.punkrecordz.totem.impl.native.NativeRootCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Serializes NBT Compound tags into a sequence of contiguous [MemorySegment] slices.
 *
 * Strategy:
 * Eliminates intermediate memory copying and gigabyte-sized buffer allocations by decomposing
 * the serialized NBT stream into disjoint memory slices. Small scalar headers and metadata
 * tags are packed into a compact off-heap scratch buffer, while large native tags (e.g. massive
 * off-heap ByteArrays containing block data) are emitted directly as zero-copy segment slices.
 *
 * Trade-offs:
 * Emitting a list of slices requires downstream consumers (such as GZIP compression codecs
 * or scatter-gather FileChannels) to support multi-slice processing.
 *
 * Performance targets:
 * Sub-millisecond serialization time for billion-block schematics with zero heap allocation
 * and zero copying of large off-heap byte arrays.
 */
object CompositeTagSerializer {

    private const val DEFAULT_LARGE_TAG_THRESHOLD_BYTES: Long = 1024 * 1024L
    private const val SCRATCH_BUFFER_CAPACITY_BYTES: Long = 4 * 1024 * 1024L

    fun serialize(
        rootName: String,
        rootCompound: CompoundTag,
        arena: Arena,
        largeTagThresholdBytes: Long = DEFAULT_LARGE_TAG_THRESHOLD_BYTES,
    ): List<MemorySegment> {
        // bypass serialization completely if root is already contiguous off-heap
        if (rootCompound is NativeRootCompoundTag && rootCompound.rootName == rootName) {
            return listOf(rootCompound.rootSegment)
        }

        val hasLargeNativePayload = rootCompound.values.any { tag ->
            findLargeNativeSegment(tag, largeTagThresholdBytes) != null
        }

        // fast path for small compounds without giant off-heap payloads
        if (!hasLargeNativePayload) {
            val headerSize = MemoryLayouts.BYTE.byteSize() +
                MemoryLayouts.SHORT.byteSize() +
                MemoryLayouts.stringByteLength(rootName)
            val contentSize = CompoundProtocol.sizeOf(rootCompound)
            val totalSize = headerSize + contentSize

            val contiguousSegment = arena.allocateUninitialized(totalSize)
            contiguousSegment.set(MemoryLayouts.BYTE, 0L, TagType.COMPOUND.id.toByte())

            val writtenNameLength = MemoryLayouts.writeString(
                contiguousSegment,
                MemoryLayouts.BYTE.byteSize(),
                rootName,
            )
            val contentOffset = MemoryLayouts.BYTE.byteSize() + writtenNameLength
            CompoundProtocol.write(contiguousSegment, contentOffset, rootCompound)

            return listOf(contiguousSegment)
        }

        val slices = mutableListOf<MemorySegment>()
        var activeScratchBuffer = arena.allocateUninitialized(SCRATCH_BUFFER_CAPACITY_BYTES)
        var currentOffset = 0L

        fun flushActiveScratch() {
            if (currentOffset > 0L) {
                slices.add(activeScratchBuffer.asSlice(0L, currentOffset))
                activeScratchBuffer = arena.allocateUninitialized(SCRATCH_BUFFER_CAPACITY_BYTES)
                currentOffset = 0L
            }
        }

        // 1. write root compound header
        activeScratchBuffer.set(MemoryLayouts.BYTE, currentOffset, TagType.COMPOUND.id.toByte())
        currentOffset += MemoryLayouts.BYTE.byteSize()
        currentOffset += MemoryLayouts.writeString(activeScratchBuffer, currentOffset, rootName)

        // 2. iterate child tags and separate inline tags from large native segments
        for ((tagKey, tagValue) in rootCompound) {
            val largeSegment = findLargeNativeSegment(tagValue, largeTagThresholdBytes)

            if (largeSegment != null) {
                // write entry tag type and tag name into scratch buffer
                val requiredHeaderSize = MemoryLayouts.BYTE.byteSize() +
                    MemoryLayouts.SHORT.byteSize() +
                    MemoryLayouts.stringByteLength(tagKey)

                if (currentOffset + requiredHeaderSize > activeScratchBuffer.byteSize()) {
                    flushActiveScratch()
                }

                activeScratchBuffer.set(MemoryLayouts.BYTE, currentOffset, tagValue.key.id.toByte())
                currentOffset += MemoryLayouts.BYTE.byteSize()
                currentOffset += MemoryLayouts.writeString(activeScratchBuffer, currentOffset, tagKey)

                // flush scratch buffer preceding the large payload
                flushActiveScratch()

                // append large native segment directly with zero copy
                slices.add(largeSegment)
            } else {
                // inline tag: write type, key and payload into scratch buffer
                val protocol = ProtocolRegistry.getOrThrow(tagValue.key.id)
                val requiredEntrySize = MemoryLayouts.BYTE.byteSize() +
                    MemoryLayouts.SHORT.byteSize() +
                    MemoryLayouts.stringByteLength(tagKey) +
                    protocol.sizeOf(tagValue)

                if (currentOffset + requiredEntrySize > activeScratchBuffer.byteSize()) {
                    flushActiveScratch()
                    if (requiredEntrySize > activeScratchBuffer.byteSize()) {
                        activeScratchBuffer = arena.allocateUninitialized(requiredEntrySize)
                    }
                }

                activeScratchBuffer.set(MemoryLayouts.BYTE, currentOffset, tagValue.key.id.toByte())
                currentOffset += MemoryLayouts.BYTE.byteSize()
                currentOffset += MemoryLayouts.writeString(activeScratchBuffer, currentOffset, tagKey)
                currentOffset += protocol.write(activeScratchBuffer, currentOffset, tagValue)
            }
        }

        // 3. write end tag and flush remaining bytes
        if (currentOffset + MemoryLayouts.BYTE.byteSize() > activeScratchBuffer.byteSize()) {
            flushActiveScratch()
        }

        activeScratchBuffer.set(MemoryLayouts.BYTE, currentOffset, TagType.END.id.toByte())
        currentOffset += MemoryLayouts.BYTE.byteSize()
        flushActiveScratch()

        return slices
    }

    private fun findLargeNativeSegment(tag: Tag, thresholdBytes: Long): MemorySegment? {
        return when (tag) {
            is NativeByteArrayTag -> {
                if (tag.sizeInBytes >= thresholdBytes) tag.segment.asSlice(0L, tag.sizeInBytes) else null
            }
            is NativeIntArrayTag -> {
                if (tag.sizeInBytes >= thresholdBytes) tag.segment.asSlice(0L, tag.sizeInBytes) else null
            }
            is NativeLongArrayTag -> {
                if (tag.sizeInBytes >= thresholdBytes) tag.segment.asSlice(0L, tag.sizeInBytes) else null
            }
            else -> null
        }
    }

}
