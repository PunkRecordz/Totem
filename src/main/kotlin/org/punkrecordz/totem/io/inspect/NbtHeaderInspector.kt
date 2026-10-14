package org.punkrecordz.totem.io.inspect

import org.punkrecordz.totem.Totem
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.io.memory.OffHeapScratchBuffer
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object NbtHeaderInspector {

    fun inspect(
        path: Path,
        arena: Arena,
        maxUncompressedBytes: Long,
    ): Pair<String, CompoundTag> = FileChannel.open(path, StandardOpenOption.READ).use { activeChannel ->
        val fileSize = activeChannel.size()
        require(fileSize >= 3L) { "File size is too small to be valid NBT: $fileSize bytes" }

        // read a lightweight 16 kb prefix initially to avoid os mmap/unmap overhead
        val initialReadLength = minOf(fileSize, 16 * 1024L)
        val prefixSegment = OffHeapScratchBuffer.getCompressionBuffer().acquire(initialReadLength)
        val actualPrefixBytes = GzipPrefixInspector.readChannelSlice(activeChannel, 0L, prefixSegment)

        inspectFromSource(
            sourceSegment = prefixSegment.asSlice(0L, actualPrefixBytes),
            sourceChannel = activeChannel,
            sourceTotalSize = fileSize,
            arena = arena,
            maxUncompressedBytes = maxUncompressedBytes,
        )
    }

    fun inspect(
        segment: MemorySegment,
        arena: Arena,
        maxUncompressedBytes: Long,
    ): Pair<String, CompoundTag> {
        require(segment.byteSize() >= 3L) {
            "Segment size is too small to be valid NBT: ${segment.byteSize()} bytes"
        }

        return inspectFromSource(segment, null, segment.byteSize(), arena, maxUncompressedBytes)
    }

    private fun inspectFromSource(
        sourceSegment: MemorySegment,
        sourceChannel: FileChannel?,
        sourceTotalSize: Long,
        arena: Arena,
        maxUncompressedBytes: Long,
    ): Pair<String, CompoundTag> {
        val activeCompression = Totem.detectCompression(sourceSegment)
        val scratchOutputSegment = OffHeapScratchBuffer.getSerializationBuffer().acquire(maxUncompressedBytes)
        val initialTargetBytes = minOf(1024L, maxUncompressedBytes)

        val initialBytesRead = if (activeCompression === CompressionType.NONE) {
            val copyLength = minOf(sourceSegment.byteSize(), initialTargetBytes)
            MemorySegment.copy(sourceSegment, 0L, scratchOutputSegment, 0L, copyLength)
            copyLength
        } else {
            GzipPrefixInspector.inflateGzipPrefixOffHeap(
                inputSegment = sourceSegment,
                startCompressedOffset = 0L,
                outputSegment = scratchOutputSegment,
                outputOffset = 0L,
                maxBytesToWrite = initialTargetBytes,
            )
        }

        if (initialBytesRead < 3L) {
            throw IllegalArgumentException("Stream contained insufficient data for NBT header: $initialBytesRead bytes")
        }

        val reader = NbtInspectReader(
            sourceSegment = sourceSegment,
            sourceChannel = sourceChannel,
            sourceTotalSize = sourceTotalSize,
            isGzip = activeCompression !== CompressionType.NONE,
            outputSegment = scratchOutputSegment,
            initialBytesRead = initialBytesRead,
            maxUncompressedBytes = maxUncompressedBytes,
        )

        val sanitizedBytes = reader.use { activeReader ->
            sanitizeAndElideInspectSegment(activeReader)
        }

        val inspectedSegment = arena.allocateUninitialized(sanitizedBytes)
        MemorySegment.copy(scratchOutputSegment, 0L, inspectedSegment, 0L, sanitizedBytes)

        return Totem.parseRootCompound(inspectedSegment)
    }

    private fun sanitizeAndElideInspectSegment(reader: NbtInspectReader): Long {
        val outputSegment = reader.outputSegment
        if (outputSegment.get(MemoryLayouts.BYTE, 0L).toInt() != TagType.COMPOUND.id) {
            return reader.currentLimit
        }

        val rootNameLength = outputSegment.get(MemoryLayouts.SHORT, 1L).toInt() and 0xFFFF
        val rootContentStart = 3L + rootNameLength
        if (rootContentStart > reader.currentLimit) {
            return reader.currentLimit
        }

        val endOffset = skipOrElidePayload(reader, TagType.COMPOUND.id, rootContentStart, 0)
        if (endOffset > 0L) return endOffset

        var closeCursor = reader.currentLimit
        while (reader.openCompounds > 0 && closeCursor < reader.maxUncompressedBytes) {
            outputSegment.set(MemoryLayouts.BYTE, closeCursor, TagType.END.id.toByte())
            closeCursor++
            reader.openCompounds--
        }

        return closeCursor
    }

    private fun skipOrElidePayload(
        reader: NbtInspectReader,
        typeId: Int,
        valueOffset: Long,
        depth: Int,
    ): Long {
        if (depth > 512) {
            throw IllegalArgumentException("NBT nesting depth exceeds maximum limit of 512 at offset $valueOffset")
        }
        if (typeId in 1..6 && reader.openCompounds <= 2) {
            reader.topLevelScalarTags++
        }

        val outputSegment = reader.outputSegment

        return when (typeId) {
            0 -> valueOffset
            1 -> if (reader.ensurePrefixAvailable(valueOffset + 1L)) valueOffset + 1L else -1L
            2 -> if (reader.ensurePrefixAvailable(valueOffset + 2L)) valueOffset + 2L else -1L
            3, 5 -> if (reader.ensurePrefixAvailable(valueOffset + 4L)) valueOffset + 4L else -1L
            4, 6 -> if (reader.ensurePrefixAvailable(valueOffset + 8L)) valueOffset + 8L else -1L
            8 -> {
                if (!reader.ensurePrefixAvailable(valueOffset + 2L)) return -1L
                val stringLength = outputSegment.get(MemoryLayouts.SHORT, valueOffset).toInt() and 0xFFFF
                val afterString = valueOffset + 2L + stringLength
                if (reader.ensurePrefixAvailable(afterString)) afterString else -1L
            }
            7, 11, 12 -> {
                if (!reader.ensurePrefixAvailable(valueOffset + 4L)) return -1L
                val elementCount = outputSegment.get(MemoryLayouts.INT, valueOffset)
                if (elementCount < 0) return -1L
                val stride = if (typeId == 7) 1L else if (typeId == 11) 4L else 8L
                val payloadStart = valueOffset + 4L
                val arrayByteSize = elementCount.toLong() * stride
                val afterArray = payloadStart + arrayByteSize

                if (arrayByteSize <= 64 * 1024L && afterArray <= reader.maxUncompressedBytes && reader.ensurePrefixAvailable(afterArray)) {
                    afterArray
                } else {
                    outputSegment.set(MemoryLayouts.INT, valueOffset, 0)
                    if (reader.topLevelScalarTags >= 3) {
                        reader.currentLimit = payloadStart
                        return -1L
                    }
                    val tailBytes = reader.readFromUncompressedOffset(
                        payloadStart + reader.uncompressedDelta + arrayByteSize,
                        payloadStart,
                    )
                    if (tailBytes > 0L) {
                        reader.uncompressedDelta += arrayByteSize
                        reader.currentLimit = payloadStart + tailBytes
                        payloadStart
                    } else {
                        reader.currentLimit = payloadStart
                        -1L
                    }
                }
            }
            9 -> {
                if (!reader.ensurePrefixAvailable(valueOffset + 5L)) return -1L
                val elementType = outputSegment.get(MemoryLayouts.BYTE, valueOffset).toInt()
                val elementCount = outputSegment.get(MemoryLayouts.INT, valueOffset + 1L)
                if (elementCount <= 0) return valueOffset + 5L
                var cursor = valueOffset + 5L
                for (elementIndex in 0 until elementCount) {
                    val nextCursor = skipOrElidePayload(reader, elementType, cursor, depth + 1)
                    if (nextCursor < 0L) {
                        outputSegment.set(MemoryLayouts.INT, valueOffset + 1L, elementIndex)
                        reader.currentLimit = cursor
                        return -1L
                    }
                    cursor = nextCursor
                }
                cursor
            }
            10 -> {
                reader.openCompounds++
                var cursor = valueOffset
                while (reader.ensurePrefixAvailable(cursor + 1L)) {
                    val fieldType = outputSegment.get(MemoryLayouts.BYTE, cursor).toInt()
                    if (fieldType == TagType.END.id) {
                        reader.openCompounds--
                        return cursor + 1L
                    }
                    if (fieldType !in 1..12 || !reader.ensurePrefixAvailable(cursor + 3L)) {
                        reader.currentLimit = cursor
                        return -1L
                    }
                    val nameLength = outputSegment.get(MemoryLayouts.SHORT, cursor + 1L).toInt() and 0xFFFF
                    val fieldValueOffset = cursor + 3L + nameLength
                    if (!reader.ensurePrefixAvailable(fieldValueOffset)) {
                        reader.currentLimit = cursor
                        return -1L
                    }
                    val nextCursor = skipOrElidePayload(reader, fieldType, fieldValueOffset, depth + 1)
                    if (nextCursor < 0L) return -1L
                    cursor = nextCursor
                }
                reader.currentLimit = cursor
                -1L
            }
            else -> -1L
        }
    }

}
