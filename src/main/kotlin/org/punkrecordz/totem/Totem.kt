package org.punkrecordz.totem

import org.punkrecordz.totem.codec.CompositeTagSerializer
import org.punkrecordz.totem.codec.CompoundProtocol
import org.punkrecordz.totem.impl.native.NativeRootCompoundTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.Compression
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.io.inspect.NbtHeaderInspector
import org.punkrecordz.totem.io.stream.BinaryTagWriter
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.contract.Tag
import java.io.File
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path

object Totem {

    fun load(
        path: Path,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> = load(TotemTagIo.mapFileReadOnly(path, arena), arena, compression)

    fun load(
        bytes: ByteArray,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> = load(MemorySegment.ofArray(bytes), arena, compression)

    fun load(
        inputStream: InputStream,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> = load(inputStream.readAllBytes(), arena, compression)

    @Throws(IllegalArgumentException::class)
    fun load(
        segment: MemorySegment,
        arena: Arena,
        compression: Compression? = null,
    ): Pair<String, CompoundTag> {
        val activeCompression = compression ?: detectCompression(segment)
        val nbtSegment = activeCompression.decompress(segment, arena)

        return parseRootCompound(nbtSegment)
    }

    fun inspect(
        path: Path,
        arena: Arena,
        maxUncompressedBytes: Long = 256 * 1024L,
    ): Pair<String, CompoundTag> = NbtHeaderInspector.inspect(path, arena, maxUncompressedBytes)

    fun inspect(
        file: File,
        arena: Arena,
        maxUncompressedBytes: Long = 256 * 1024L,
    ): Pair<String, CompoundTag> = inspect(file.toPath(), arena, maxUncompressedBytes)

    fun inspect(
        segment: MemorySegment,
        arena: Arena,
        maxUncompressedBytes: Long = 256 * 1024L,
    ): Pair<String, CompoundTag> = NbtHeaderInspector.inspect(segment, arena, maxUncompressedBytes)

    internal fun parseRootCompound(nbtSegment: MemorySegment): Pair<String, CompoundTag> {
        val rootTypeId = nbtSegment.get(MemoryLayouts.BYTE, 0L).toInt()
        require(rootTypeId == TagType.COMPOUND.id) {
            "Expected root Compound tag (${TagType.COMPOUND.id}), but found ID: $rootTypeId"
        }

        val nameLength = nbtSegment.get(MemoryLayouts.SHORT, 1L).toInt() and 0xFFFF
        val headerSize = MemoryLayouts.BYTE.byteSize() + MemoryLayouts.SHORT.byteSize() + nameLength

        require(nbtSegment.byteSize() >= headerSize) {
            "NBT segment size (${nbtSegment.byteSize()}) is smaller than root header ($headerSize)"
        }

        val rootName = MemoryLayouts.readString(nbtSegment, 1L)
        val contentSegment = nbtSegment.asSlice(headerSize)
        val rootTag = CompoundProtocol.read(contentSegment, 0L)

        return rootName to NativeRootCompoundTag(nbtSegment, rootName, rootTag)
    }

    internal fun detectCompression(segment: MemorySegment): Compression {
        if (segment.byteSize() < 2L) return CompressionType.NONE

        return when (segment.get(MemoryLayouts.SHORT, 0L).toInt() and 0xFFFF) {
            0x1F8B -> CompressionType.GZIP
            0x7801, 0x785E, 0x789C, 0x78DA -> CompressionType.ZLIB
            else -> CompressionType.NONE
        }
    }

    fun save(
        name: String,
        root: CompoundTag,
        arena: Arena,
        compression: Compression = CompressionType.NONE,
    ): MemorySegment {
        if (root is NativeRootCompoundTag && root.rootName == name) {
            if (compression === CompressionType.NONE) {
                val uncompressedSegment = arena.allocateUninitialized(root.rootSegment.byteSize())
                MemorySegment.copy(root.rootSegment, 0L, uncompressedSegment, 0L, root.rootSegment.byteSize())
                return uncompressedSegment
            }

            return compression.compress(root.rootSegment, arena)
        }

        if (compression === CompressionType.NONE) {
            val headerSize =
                MemoryLayouts.BYTE.byteSize() + MemoryLayouts.SHORT.byteSize() + MemoryLayouts.stringByteLength(name)
            val contentSize = CompoundProtocol.sizeOf(root)
            val uncompressedSegment = arena.allocateUninitialized(headerSize + contentSize)

            TotemTagIo.writeUncompressedCompound(uncompressedSegment, name, root, contentSize)
            return uncompressedSegment
        }

        return Arena.ofConfined().use { tempArena ->
            val slices = CompositeTagSerializer.serialize(name, root, tempArena)
            compression.compress(slices, arena)
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
            TotemTagIo.writeSegmentToFile(path, segment)
        }
    }

    fun write(
        path: Path,
        rootName: String? = null,
        compression: Compression = CompressionType.NONE,
        block: BinaryTagWriter.() -> Unit,
    ) = TotemTagIo.writeDsl(path, rootName, compression, block)

    fun saveToByteArray(
        name: String,
        root: CompoundTag,
        compression: Compression = CompressionType.NONE,
    ): ByteArray = Arena.ofConfined().use { arena ->
        save(name, root, arena, compression).toArray(ValueLayout.JAVA_BYTE)
    }

    fun <T : Tag> loadTag(
        path: Path,
        arena: Arena,
        compression: Compression? = null,
    ): T = loadTag(TotemTagIo.mapFileReadOnly(path, arena), arena, compression)

    fun <T : Tag> loadTag(
        bytes: ByteArray,
        arena: Arena,
        compression: Compression? = null,
    ): T = loadTag(MemorySegment.ofArray(bytes), arena, compression)

    fun <T : Tag> loadTag(
        inputStream: InputStream,
        arena: Arena,
        compression: Compression? = null,
    ): T = loadTag(inputStream.readAllBytes(), arena, compression)

    fun <T : Tag> loadTag(
        segment: MemorySegment,
        arena: Arena,
        compression: Compression? = null,
    ): T = TotemTagIo.loadTag(segment, arena, compression)

    fun saveTag(
        tag: Tag,
        arena: Arena,
        compression: Compression = CompressionType.NONE,
    ): MemorySegment = TotemTagIo.saveTag(tag, arena, compression)

    fun saveTag(
        tag: Tag,
        path: Path,
        compression: Compression = CompressionType.NONE,
    ) {
        Arena.ofConfined().use { arena ->
            TotemTagIo.writeSegmentToFile(path, saveTag(tag, arena, compression))
        }
    }

    fun saveTagToByteArray(
        tag: Tag,
        compression: Compression = CompressionType.NONE,
    ): ByteArray = TotemTagIo.saveTagToByteArray(tag, compression)

}
