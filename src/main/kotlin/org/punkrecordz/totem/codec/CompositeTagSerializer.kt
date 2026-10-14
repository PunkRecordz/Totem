package org.punkrecordz.totem.codec

import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.impl.native.NativeCompoundTag
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
import java.util.IdentityHashMap

object CompositeTagSerializer {

    private const val DEFAULT_LARGE_TAG_THRESHOLD_BYTES: Long = 1024 * 1024L
    private const val INITIAL_SCRATCH_CAPACITY_BYTES: Long = 64 * 1024L
    private const val SCRATCH_BUFFER_CAPACITY_BYTES: Long = 4 * 1024 * 1024L

    fun serialize(
        rootName: String,
        rootCompound: CompoundTag,
        arena: Arena,
        largeTagThresholdBytes: Long = DEFAULT_LARGE_TAG_THRESHOLD_BYTES,
    ): List<MemorySegment> {
        if (rootCompound is NativeRootCompoundTag && rootCompound.rootName == rootName) {
            return listOf(rootCompound.rootSegment)
        }

        val largeCompoundCache = IdentityHashMap<CompoundTag, Boolean>()
        val nativeCompoundSizeCache = HashMap<MemorySegment, Long>()
        val hasLargeNativePayload = containsLargeNativeSegment(
            rootCompound,
            largeTagThresholdBytes,
            largeCompoundCache,
            nativeCompoundSizeCache,
        )

        if (!hasLargeNativePayload) {
            val headerSize =
                MemoryLayouts.BYTE.byteSize() + MemoryLayouts.SHORT.byteSize() + MemoryLayouts.stringByteLength(rootName)
            val contentSize = CompoundProtocol.sizeOf(rootCompound)
            val contiguousSegment = arena.allocateUninitialized(headerSize + contentSize)

            contiguousSegment.set(MemoryLayouts.BYTE, 0L, TagType.COMPOUND.id.toByte())
            val writtenNameLength = MemoryLayouts.writeString(contiguousSegment, MemoryLayouts.BYTE.byteSize(), rootName)
            CompoundProtocol.write(contiguousSegment, MemoryLayouts.BYTE.byteSize() + writtenNameLength, rootCompound)

            return listOf(contiguousSegment)
        }

        val slices = mutableListOf<MemorySegment>()
        var activeScratchBuffer = arena.allocateUninitialized(INITIAL_SCRATCH_CAPACITY_BYTES)
        var sliceStartOffset = 0L
        var currentOffset = 0L

        fun flushActiveScratch() {
            val sliceLength = currentOffset - sliceStartOffset
            if (sliceLength > 0L) {
                slices.add(activeScratchBuffer.asSlice(sliceStartOffset, sliceLength))
                sliceStartOffset = currentOffset
            }
        }

        fun ensureScratchCapacity(requiredBytes: Long) {
            if (currentOffset + requiredBytes > activeScratchBuffer.byteSize()) {
                flushActiveScratch()
                val nextCapacity = maxOf(SCRATCH_BUFFER_CAPACITY_BYTES, requiredBytes)
                activeScratchBuffer = arena.allocateUninitialized(nextCapacity)
                sliceStartOffset = 0L
                currentOffset = 0L
            }
        }

        fun writeEntryHeader(
            typeId: Int,
            tagKey: String,
            additionalPayloadBytes: Long = 0L,
        ) {
            val requiredHeaderSize = MemoryLayouts.BYTE.byteSize() +
                MemoryLayouts.SHORT.byteSize() +
                MemoryLayouts.stringByteLength(tagKey) +
                additionalPayloadBytes

            ensureScratchCapacity(requiredHeaderSize)

            activeScratchBuffer.set(MemoryLayouts.BYTE, currentOffset, typeId.toByte())
            currentOffset += MemoryLayouts.BYTE.byteSize()
            currentOffset += MemoryLayouts.writeString(activeScratchBuffer, currentOffset, tagKey)
        }

        fun writeEndTag() {
            ensureScratchCapacity(MemoryLayouts.BYTE.byteSize())
            activeScratchBuffer.set(MemoryLayouts.BYTE, currentOffset, TagType.END.id.toByte())
            currentOffset += MemoryLayouts.BYTE.byteSize()
        }

        fun writeStandardEntry(
            tagKey: String,
            tagValue: Tag,
        ) {
            val protocol = ProtocolRegistry.getOrThrow(tagValue.key.id)
            val payloadSize = if (tagValue is NativeCompoundTag) {
                nativeCompoundSizeCache.getOrPut(tagValue.segment) { CompoundProtocol.sizeOf(tagValue) }
            } else {
                protocol.sizeOf(tagValue)
            }

            writeEntryHeader(tagValue.key.id, tagKey, payloadSize)
            currentOffset += protocol.write(activeScratchBuffer, currentOffset, tagValue, payloadSize)
        }

        fun writeCompoundContent(compound: CompoundTag) {
            // pass 1a: serialize lightweight scalar tags first so Version/DataVersion/dimensions are in the first ~128 bytes
            for ((tagKey, tagValue) in compound) {
                val typeId = tagValue.key.id
                if (typeId in 1..6 || typeId == 8) {
                    writeStandardEntry(tagKey, tagValue)
                }
            }

            // pass 1b: serialize non-scalar metadata tags (small arrays, lists, and compounds without large native payloads)
            for ((tagKey, tagValue) in compound) {
                val typeId = tagValue.key.id
                if (typeId in 1..6 || typeId == 8) {
                    continue
                }

                val hasLargePayload = containsLargeNativeSegment(
                    tagValue,
                    largeTagThresholdBytes,
                    largeCompoundCache,
                    nativeCompoundSizeCache,
                )

                if (hasLargePayload) {
                    continue
                }

                writeStandardEntry(tagKey, tagValue)
            }

            // pass 2: emit large zero-copy native payloads and compounds containing them at the end
            for ((tagKey, tagValue) in compound) {
                val largeSegment = findLargeNativeSegment(
                    tagValue,
                    largeTagThresholdBytes,
                    nativeCompoundSizeCache,
                )

                if (largeSegment != null) {
                    writeEntryHeader(tagValue.key.id, tagKey)
                    flushActiveScratch()
                    slices.add(largeSegment)
                } else if (
                    tagValue is CompoundTag &&
                    containsLargeNativeSegment(
                        tagValue,
                        largeTagThresholdBytes,
                        largeCompoundCache,
                        nativeCompoundSizeCache,
                    )
                ) {
                    writeEntryHeader(TagType.COMPOUND.id, tagKey)
                    writeCompoundContent(tagValue)
                    writeEndTag()
                }
            }
        }

        writeEntryHeader(TagType.COMPOUND.id, rootName)
        writeCompoundContent(rootCompound)
        writeEndTag()
        flushActiveScratch()

        return slices
    }

    private fun containsLargeNativeSegment(
        tag: Tag,
        thresholdBytes: Long,
        cache: IdentityHashMap<CompoundTag, Boolean>,
        nativeCompoundSizeCache: HashMap<MemorySegment, Long>,
        depth: Int = 0,
    ): Boolean {
        if (depth > CompoundProtocol.MAX_NBT_DEPTH) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of ${CompoundProtocol.MAX_NBT_DEPTH}")
        }

        if (findLargeNativeSegment(tag, thresholdBytes, nativeCompoundSizeCache) != null) {
            return true
        }

        if (tag is CompoundTag && tag !is NativeCompoundTag) {
            return cache.getOrPut(tag) {
                tag.any { (_, childTag) ->
                    containsLargeNativeSegment(childTag, thresholdBytes, cache, nativeCompoundSizeCache, depth + 1)
                }
            }
        }

        return false
    }

    private fun findLargeNativeSegment(
        tag: Tag,
        thresholdBytes: Long,
        nativeCompoundSizeCache: HashMap<MemorySegment, Long>,
    ): MemorySegment? = when (tag) {
        is NativeByteArrayTag -> tag.segment.asSlice(0L, tag.sizeInBytes).takeIf { tag.sizeInBytes >= thresholdBytes }
        is NativeIntArrayTag -> tag.segment.asSlice(0L, tag.sizeInBytes).takeIf { tag.sizeInBytes >= thresholdBytes }
        is NativeLongArrayTag -> tag.segment.asSlice(0L, tag.sizeInBytes).takeIf { tag.sizeInBytes >= thresholdBytes }
        is NativeCompoundTag -> {
            if (tag.segment.byteSize() < thresholdBytes) {
                null
            } else {
                val exactSize = nativeCompoundSizeCache.getOrPut(tag.segment) { CompoundProtocol.sizeOf(tag) }
                if (exactSize >= thresholdBytes) tag.segment.asSlice(0L, exactSize) else null
            }
        }
        else -> null
    }

}
