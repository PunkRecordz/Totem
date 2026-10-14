package org.punkrecordz.totem

import org.punkrecordz.totem.codec.CompoundProtocol
import org.punkrecordz.totem.codec.ProtocolRegistry
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.Compression
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.io.stream.BinaryTagWriter
import org.punkrecordz.totem.io.stream.NativeBinaryTagWriter
import org.punkrecordz.totem.io.stream.NativeByteArrayOutputStream
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object TotemTagIo {

    fun writeDsl(
        path: Path,
        rootName: String?,
        compression: Compression,
        block: BinaryTagWriter.() -> Unit,
    ) {
        if (compression === CompressionType.NONE) {
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
                StandardOpenOption.TRUNCATE_EXISTING,
            )

            channel.use { activeChannel ->
                val totalWritten = Arena.ofConfined().use { tempArena ->
                    val outputStream = NativeByteArrayOutputStream(tempArena, activeChannel)
                    buildDsl(outputStream, rootName, block)
                    outputStream.totalWritten
                }
                activeChannel.truncate(totalWritten)
            }
        } else {
            Arena.ofConfined().use { tempArena ->
                val outputStream = NativeByteArrayOutputStream(tempArena, null)
                buildDsl(outputStream, rootName, block)

                val uncompressedSegment = outputStream.toContiguousSegment(tempArena)
                val compressedSegment = compression.compress(uncompressedSegment, tempArena)
                writeSegmentToFile(path, compressedSegment)
            }
        }
    }

    private inline fun buildDsl(
        outputStream: NativeByteArrayOutputStream,
        rootName: String?,
        block: BinaryTagWriter.() -> Unit,
    ) {
        val writer = NativeBinaryTagWriter(outputStream)

        outputStream.writeByte(TagType.COMPOUND.id.toByte())
        if (rootName != null) {
            outputStream.writeString(rootName)
        } else {
            outputStream.writeShort(0)
        }
        writer.block()
        outputStream.writeByte(TagType.END.id.toByte())
    }

    fun writeUncompressedCompound(
        segment: MemorySegment,
        name: String,
        root: CompoundTag,
        precalculatedSize: Long = -1L,
    ) {
        segment.set(MemoryLayouts.BYTE, 0L, TagType.COMPOUND.id.toByte())
        val writtenLength = MemoryLayouts.writeString(segment, MemoryLayouts.BYTE.byteSize(), name)
        CompoundProtocol.write(segment, MemoryLayouts.BYTE.byteSize() + writtenLength, root, precalculatedSize)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Tag> loadTag(
        segment: MemorySegment,
        arena: Arena,
        compression: Compression?,
    ): T {
        val activeCompression = compression ?: Totem.detectCompression(segment)
        val nbtSegment = activeCompression.decompress(segment, arena)

        val rootTypeId = nbtSegment.get(MemoryLayouts.BYTE, 0L).toInt()
        val protocol = ProtocolRegistry.getOrThrow(rootTypeId)

        return protocol.read(nbtSegment, MemoryLayouts.BYTE.byteSize()) as T
    }

    fun saveTag(
        tag: Tag,
        arena: Arena,
        compression: Compression,
    ): MemorySegment {
        val protocol = ProtocolRegistry.getOrThrow(tag.key.id)
        val totalSize = MemoryLayouts.BYTE.byteSize() + protocol.sizeOf(tag)

        if (compression === CompressionType.NONE) {
            val uncompressedSegment = arena.allocateUninitialized(totalSize)
            writeUncompressedTag(uncompressedSegment, tag)
            return uncompressedSegment
        }

        return Arena.ofConfined().use { tempArena ->
            val uncompressedSegment = tempArena.allocateUninitialized(totalSize)
            writeUncompressedTag(uncompressedSegment, tag)
            compression.compress(uncompressedSegment, arena)
        }
    }

    fun saveTagToByteArray(
        tag: Tag,
        compression: Compression,
    ): ByteArray = Arena.ofConfined().use { arena ->
        saveTag(tag, arena, compression).toArray(ValueLayout.JAVA_BYTE)
    }

    fun mapFileReadOnly(
        path: Path,
        arena: Arena,
    ): MemorySegment = FileChannel.open(path, StandardOpenOption.READ).use { activeChannel ->
        activeChannel.map(FileChannel.MapMode.READ_ONLY, 0L, activeChannel.size(), arena)
    }

    fun writeSegmentToFile(
        path: Path,
        segment: MemorySegment,
    ) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { activeChannel ->
            activeChannel.write(segment.asByteBuffer())
        }
    }

    private fun writeUncompressedTag(
        segment: MemorySegment,
        tag: Tag,
    ) {
        segment.set(MemoryLayouts.BYTE, 0L, tag.key.id.toByte())
        val protocol = ProtocolRegistry.getOrThrow(tag.key.id)
        protocol.write(segment, MemoryLayouts.BYTE.byteSize(), tag)
    }

}
