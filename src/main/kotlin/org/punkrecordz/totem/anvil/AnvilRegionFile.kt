package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.Totem
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.CompoundTag
import java.io.File
import java.io.Flushable
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

class AnvilRegionFile(
    file: File,
    val writeMode: Boolean = false,
    val truncateExisting: Boolean = false,
    val compressionLevel: Int = DEFAULT_COMPRESSION_LEVEL,
    val parallelCompression: Boolean = false,
) : AutoCloseable, Flushable {

    @Deprecated("Binary compatibility constructor", level = DeprecationLevel.HIDDEN)
    constructor(
        file: File,
        writeMode: Boolean = false,
    ) : this(
        file = file,
        writeMode = writeMode,
        truncateExisting = false,
    )

    private val sectorTable: AnvilSectorTable = AnvilSectorTable()
    private val access: AnvilRegionAccess

    init {
        require(!truncateExisting || writeMode) {
            "truncateExisting requires writeMode = true."
        }
        require(compressionLevel in MIN_COMPRESSION_LEVEL..MAX_COMPRESSION_LEVEL) {
            "compressionLevel must be in $MIN_COMPRESSION_LEVEL..$MAX_COMPRESSION_LEVEL, but was $compressionLevel"
        }

        access = AnvilRegionAccess(
            file = file,
            writeMode = writeMode,
            truncateExisting = truncateExisting,
            parallelCompression = parallelCompression,
            compressionLevel = compressionLevel,
            sectorTable = sectorTable,
        )
    }

    fun listChunks(): List<ChunkCoordinate> = access.read { sectorTable.listPresentChunks() }

    fun readChunk(
        globalX: Int,
        globalZ: Int,
        targetArena: Arena,
    ): Pair<String, CompoundTag>? = access.read { activeState ->
        val validatedPayload = readValidatedChunkPayload(activeState, globalX, globalZ) ?: return@read null
        val payloadSlice = validatedPayload.payload

        val ownedNbtSegment = when (validatedPayload.compressionCode) {
            CompressedChunkPayload.COMPRESSION_GZIP -> CompressionType.GZIP.decompress(payloadSlice, targetArena)
            CompressedChunkPayload.COMPRESSION_ZLIB -> CompressionType.ZLIB.decompress(payloadSlice, targetArena)
            // copied so the returned view never aliases a recycled pooled region buffer
            else -> targetArena.allocateUninitialized(payloadSlice.byteSize()).copyFrom(payloadSlice)
        }

        Totem.parseRootCompound(ownedNbtSegment)
    }

    fun readDecompressedChunkSlice(
        chunkX: Int,
        chunkZ: Int,
    ): MemorySegment? = access.read { activeState ->
        readValidatedChunkPayload(activeState, chunkX, chunkZ)?.let { validatedPayload ->
            AnvilChunkReader.decompressTransient(validatedPayload)
        }
    }

    fun readChunkTransient(
        chunkX: Int,
        chunkZ: Int,
    ): Pair<String, CompoundTag>? {
        val transientNbtSlice = readDecompressedChunkSlice(chunkX, chunkZ) ?: return null
        return Totem.parseRootCompound(transientNbtSlice)
    }

    @JvmOverloads
    fun readCompressedChunk(
        chunkX: Int,
        chunkZ: Int,
        targetArena: Arena = Arena.ofAuto(),
    ): CompressedChunkPayload? = access.read { activeState ->
        readValidatedChunkPayload(activeState, chunkX, chunkZ)?.let { transientPayload ->
            val payloadBytes = transientPayload.payload.byteSize()
            val ownedPayload = targetArena.allocateUninitialized(payloadBytes)
            if (payloadBytes > 0L) {
                MemorySegment.copy(transientPayload.payload, 0L, ownedPayload, 0L, payloadBytes)
            }

            CompressedChunkPayload(transientPayload.compressionCode, ownedPayload)
        }
    }

    fun forEachDecompressedChunk(
        parallel: Boolean = true,
        consumer: ChunkSliceConsumer,
    ) {
        if (!writeMode) {
            access.read { activeState ->
                val loadedState = activeState as AnvilRegionState.ReadOnlyLoaded
                AnvilBatchInflater.forEach(loadedState, sectorTable, parallel, consumer)
            }
            return
        }

        // writable regions release their lock between chunks so consumers may write back safely without deadlocking
        for (coordinate in listChunks()) {
            val uncompressedNbt = readDecompressedChunkSlice(coordinate.x, coordinate.z) ?: continue
            consumer.accept(coordinate.x, coordinate.z, uncompressedNbt)
        }
    }

    fun forEachChunkTransient(
        parallel: Boolean = true,
        consumer: TransientChunkConsumer,
    ) = forEachDecompressedChunk(parallel) { chunkX, chunkZ, uncompressedNbt ->
        val (rootName, compound) = Totem.parseRootCompound(uncompressedNbt)
        consumer.accept(chunkX, chunkZ, rootName, compound)
    }

    fun writeChunk(
        globalX: Int,
        globalZ: Int,
        rootName: String,
        tag: CompoundTag,
    ) {
        ensureWritableBeforeEncode()
        val chunkIndex = AnvilSectorTable.getChunkIndex(globalX, globalZ)
        val encodeScratch = AnvilChunkEncodeScratch.get()

        when (access.isStaging) {
            true -> access.stageRawChunk(chunkIndex, encodeScratch.serializeCompound(rootName, tag))
            false -> {
                val framedSectors = encodeScratch.encodeCompoundToFramedSectors(rootName, tag, compressionLevel)
                access.commitFramedSectors(chunkIndex, framedSectors)
            }
        }
    }

    fun writeRawChunkNbt(
        chunkX: Int,
        chunkZ: Int,
        uncompressedNbt: MemorySegment,
    ) {
        ensureWritableBeforeEncode()
        val chunkIndex = AnvilSectorTable.getChunkIndex(chunkX, chunkZ)

        when (access.isStaging) {
            true -> access.stageRawChunk(chunkIndex, uncompressedNbt)
            false -> {
                val framedSectors = AnvilChunkEncodeScratch.get()
                    .encodeRawNbtToFramedSectors(uncompressedNbt, compressionLevel)
                access.commitFramedSectors(chunkIndex, framedSectors)
            }
        }
    }

    fun writeCompressedChunk(
        chunkX: Int,
        chunkZ: Int,
        compressedChunk: CompressedChunkPayload,
    ) {
        ensureWritableBeforeEncode()
        val chunkIndex = AnvilSectorTable.getChunkIndex(chunkX, chunkZ)
        val framedSectors = AnvilChunkEncodeScratch.get()
            .encodeCompressedToFramedSectors(compressedChunk.compressionCode, compressedChunk.payload)

        access.commitFramedSectors(chunkIndex, framedSectors)
    }

    fun copyChunkTo(
        target: AnvilRegionFile,
        chunkX: Int,
        chunkZ: Int,
    ): Boolean {
        target.ensureWritableBeforeEncode()
        val encodeScratch = AnvilChunkEncodeScratch.get()

        // frame into encodeScratch while holding the source read lock, then commit after releasing it (safe if target === this)
        val framedSectors = access.read { activeState ->
            val validatedPayload = readValidatedChunkPayload(activeState, chunkX, chunkZ) ?: return@read null
            encodeScratch.encodeCompressedToFramedSectors(validatedPayload.compressionCode, validatedPayload.payload)
        } ?: return false

        target.access.commitFramedSectors(AnvilSectorTable.getChunkIndex(chunkX, chunkZ), framedSectors)
        return true
    }

    fun writeFramedChunkSectors(
        chunkX: Int,
        chunkZ: Int,
        framedSectors: MemorySegment,
    ) {
        ensureWritableBeforeEncode()
        val byteSize = framedSectors.byteSize()
        require(byteSize > 0L && byteSize % AnvilSectorTable.SECTOR_SIZE.toLong() == 0L) {
            "Framed chunk sectors size ($byteSize bytes) must be a positive multiple of ${AnvilSectorTable.SECTOR_SIZE}."
        }

        val requiredSectors = (byteSize / AnvilSectorTable.SECTOR_SIZE.toLong()).toInt()
        require(requiredSectors <= AnvilChunkEncodeScratch.MAX_CHUNK_SECTORS) {
            "Framed chunk sectors count ($requiredSectors) exceeds maximum limit of ${AnvilChunkEncodeScratch.MAX_CHUNK_SECTORS} sectors."
        }

        access.commitFramedSectors(AnvilSectorTable.getChunkIndex(chunkX, chunkZ), framedSectors)
    }

    override fun flush() = access.flush()

    override fun close() = access.close()

    private fun readValidatedChunkPayload(
        activeState: AnvilRegionState,
        chunkX: Int,
        chunkZ: Int,
    ): CompressedChunkPayload? {
        val chunkIndex = AnvilSectorTable.getChunkIndex(chunkX, chunkZ)
        return AnvilChunkReader.readValidatedPayload(activeState, sectorTable.offsets[chunkIndex])
    }

    private fun ensureWritableBeforeEncode() {
        require(writeMode) { "Cannot write chunk on a read-only AnvilRegionFile mapping." }
        access.ensureOpen()
    }

    companion object {

        const val DEFAULT_COMPRESSION_LEVEL: Int = AnvilChunkEncodeScratch.DEFAULT_ZLIB_LEVEL
        const val MIN_COMPRESSION_LEVEL: Int = 0
        const val MAX_COMPRESSION_LEVEL: Int = 12

    }

}
