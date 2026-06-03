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
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption


object Totem {

    fun load(
        path: Path,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> {
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        val rawSegment = channel.use { activeChannel ->
            activeChannel.map(
                FileChannel.MapMode.READ_ONLY,
                0L,
                activeChannel.size(),
                arena,
            )
        }

        return load(rawSegment, arena, compression)
    }

    fun load(
        bytes: ByteArray,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> {
        val segment = MemorySegment.ofArray(bytes)
        return load(segment, arena, compression)
    }

    fun load(
        input: InputStream,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> {
        val bytes = input.readAllBytes()
        return load(bytes, arena, compression)
    }

    @Throws(IllegalArgumentException::class)
    fun load(
        segment: MemorySegment,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> {
        val activeCompression = compression ?: detectCompression(segment)
        val nbtSegment = activeCompression.decompress(segment, arena)

        val rootTypeId = nbtSegment.get(MemoryLayouts.BYTE, 0L).toInt()
        if (rootTypeId != 10) {
            throw IllegalArgumentException("Expected root Compound tag (10), but found ID: $rootTypeId")
        }

        val rootName = MemoryLayouts.readString(nbtSegment, 1L)
        val nameLength = nbtSegment.get(MemoryLayouts.SHORT, 1L).toInt() and 0xFFFF

        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.SHORT.byteSize() + nameLength
        val contentSegment = nbtSegment.asSlice(headerSize)

        val rootTag = CompoundProtocol.read(contentSegment, 0L)

        return rootName to rootTag
    }

    private fun detectCompression(segment: MemorySegment): Compression {
        if (segment.byteSize() < 2) return CompressionType.NONE

        // 0x1f8b is the standard gzip magic header bytes in little-endian
        val magic = segment.get(MemoryLayouts.SHORT, 0L).toInt() and 0xFFFF
        return if (magic == 0x1F8B) CompressionType.GZIP else CompressionType.NONE
    }

    fun save(
        name: String,
        root: CompoundTag,
        arena: Arena,
        compression: Compression = CompressionType.NONE,
    ): MemorySegment {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size.toShort()
        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.SHORT.byteSize() + nameBytes.size

        val contentSize = CompoundProtocol.sizeOf(root)
        val totalSize = headerSize + contentSize

        if (compression === CompressionType.NONE) {
            val uncompressedSegment = arena.allocateUninitialized(totalSize)
            writeUncompressed(uncompressedSegment, nameBytes, nameLength, root)
            return uncompressedSegment
        }

        return Arena.ofConfined().use { tempArena ->
            val uncompressedSegment = tempArena.allocateUninitialized(totalSize)
            writeUncompressed(uncompressedSegment, nameBytes, nameLength, root)
            compression.compress(uncompressedSegment, arena)
        }
    }

    fun save(
        name: String,
        root: CompoundTag,
        path: Path,
        compression: Compression = CompressionType.NONE,
    ) {
        Arena.ofConfined().use { arena ->
            val segment = save(name, root, arena, compression)
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            channel.use { activeChannel ->
                activeChannel.write(segment.asByteBuffer())
            }
        }
    }

    fun write(
        path: Path,
        rootName: String? = null,
        compression: Compression = CompressionType.NONE,
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

            var totalWritten = 0L

            channel.use { activeChannel ->
                Arena.ofConfined().use { tempArena ->
                    val outputStream = NativeByteArrayOutputStream(tempArena, activeChannel)

                    buildDsl(outputStream, rootName, block)
                    totalWritten = outputStream.totalWritten
                }

                activeChannel.truncate(totalWritten)
            }
        } else {
            Arena.ofConfined().use { tempArena ->
                val outputStream = NativeByteArrayOutputStream(tempArena, null)

                buildDsl(outputStream, rootName, block)

                val uncompressedSegment = outputStream.toContiguousSegment(tempArena)
                val compressedSegment = compression.compress(uncompressedSegment, tempArena)

                val writeChannel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )

                writeChannel.use { activeWriteChannel ->
                    activeWriteChannel.write(compressedSegment.asByteBuffer())
                }
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

    fun saveToByteArray(
        name: String,
        root: CompoundTag,
        compression: Compression = CompressionType.NONE,
    ): ByteArray {
        return Arena.ofConfined().use { arena ->
            val segment = save(name, root, arena, compression)
            segment.toArray(ValueLayout.JAVA_BYTE)
        }
    }


    private fun writeUncompressed(segment: MemorySegment, nameBytes: ByteArray, nameLength: Short, root: CompoundTag) {
        segment.set(MemoryLayouts.BYTE, 0L, TagType.COMPOUND.id.toByte())

        var offset = MemoryLayouts.BYTE.byteSize()
        segment.set(MemoryLayouts.SHORT, offset, nameLength)
        offset += MemoryLayouts.SHORT.byteSize()

        MemorySegment.copy(MemorySegment.ofArray(nameBytes), 0, segment, offset, nameBytes.size.toLong())
        offset += nameBytes.size.toLong()

        CompoundProtocol.write(segment, offset, root)
    }


    fun <T : Tag> loadTag(
        path: Path,
        arena: Arena,
        compression: Compression? = null,
    ): T {
        val rawSegment = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size(), arena)
        }

        return loadTag(rawSegment, arena, compression)
    }

    fun <T : Tag> loadTag(
        bytes: ByteArray,
        arena: Arena,
        compression: Compression? = null,
    ): T {
        val segment = MemorySegment.ofArray(bytes)
        return loadTag(segment, arena, compression)
    }

    fun <T : Tag> loadTag(
        input: InputStream,
        arena: Arena,
        compression: Compression? = null,
    ): T {
        val bytes = input.readAllBytes()
        return loadTag(bytes, arena, compression)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Tag> loadTag(
        segment: MemorySegment,
        arena: Arena,
        compression: Compression? = null,
    ): T {
        val activeCompression = compression ?: detectCompression(segment)
        val nbtSegment = activeCompression.decompress(segment, arena)

        val rootTypeId = nbtSegment.get(MemoryLayouts.BYTE, 0L).toInt()
        val protocol = ProtocolRegistry.getOrThrow(rootTypeId)

        return protocol.read(nbtSegment, MemoryLayouts.BYTE.byteSize()) as T
    }

    fun saveTag(
        tag: Tag,
        arena: Arena,
        compression: Compression = CompressionType.NONE,
    ): MemorySegment {
        val protocol = ProtocolRegistry.getOrThrow(tag.key.id)
        val headerSize = MemoryLayouts.BYTE.byteSize()
        val contentSize = protocol.sizeOf(tag)
        val totalSize = headerSize + contentSize

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

    fun saveTag(
        tag: Tag,
        path: Path,
        compression: Compression = CompressionType.NONE,
    ) {
        Arena.ofConfined().use { arena ->
            val segment = saveTag(tag, arena, compression)
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            channel.use {
                it.write(segment.asByteBuffer())
            }
        }
    }

    fun saveTagToByteArray(
        tag: Tag,
        compression: Compression = CompressionType.NONE,
    ): ByteArray {
        return Arena.ofConfined().use { arena ->
            val segment = saveTag(tag, arena, compression)
            segment.toArray(ValueLayout.JAVA_BYTE)
        }
    }

    private fun writeUncompressedTag(segment: MemorySegment, tag: Tag) {
        segment.set(MemoryLayouts.BYTE, 0L, tag.key.id.toByte())
        val protocol = ProtocolRegistry.getOrThrow(tag.key.id)
        protocol.write(segment, MemoryLayouts.BYTE.byteSize(), tag)
    }

}
