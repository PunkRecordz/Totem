package org.punkrecordz.totem.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Foreign Function & Memory (FFM) bridge for the high-performance native `totem-sys` companion library.
 *
 * Strategy:
 * Uses Project Panama's downcall handles equipped with [Linker.Option.critical] (`allowHeapAccess = false`)
 * to eliminate safepoints, thread-state switches, and FFI transition overhead for intensive batch
 * encoding/decoding of VarInt data directly on raw [MemorySegment] instances.
 *
 * Performance targets:
 * Sub-millisecond execution for millions of block states through SIMD/SWAR parallelized native routines.
 */
object TotemSys {

    private val nativeLinker = Linker.nativeLinker()

    private val nativeLibrary: SymbolLookup by lazy {
        loadNativeLibrary()
    }

    private val decodeDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
    )

    private val encodeDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
    )

    private val sizeDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
    )

    private val countTerminalsDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
    )

    private val criticalOption = Linker.Option.critical(false)

    private val decodeMethodHandle by lazy {
        val symbol = nativeLibrary.find("decode_varint_shorts")
            .orElseThrow { UnsatisfiedLinkError("Symbol decode_varint_shorts not found in totem-sys native library") }
        nativeLinker.downcallHandle(symbol, decodeDescriptor, criticalOption)
    }

    private val encodeMethodHandle by lazy {
        val symbol = nativeLibrary.find("encode_shorts_varint")
            .orElseThrow { UnsatisfiedLinkError("Symbol encode_shorts_varint not found in totem-sys native library") }
        nativeLinker.downcallHandle(symbol, encodeDescriptor, criticalOption)
    }

    private val sizeMethodHandle by lazy {
        val symbol = nativeLibrary.find("varint_encoded_size")
            .orElseThrow { UnsatisfiedLinkError("Symbol varint_encoded_size not found in totem-sys native library") }
        nativeLinker.downcallHandle(symbol, sizeDescriptor, criticalOption)
    }

    private val countTerminalsMethodHandle by lazy {
        val symbol = nativeLibrary.find("count_varint_terminals")
            .orElseThrow { UnsatisfiedLinkError("Symbol count_varint_terminals not found in totem-sys native library") }
        nativeLinker.downcallHandle(symbol, countTerminalsDescriptor, criticalOption)
    }

    fun decodeVarIntShorts(
        sourceSlice: MemorySegment,
        destinationSlice: MemorySegment,
        expectedSize: Int,
    ): Long {
        val result = decodeMethodHandle.invokeExact(
            sourceSlice,
            sourceSlice.byteSize(),
            destinationSlice,
            expectedSize,
        ) as Long

        if (result < 0L) {
            when (result) {
                -1L -> throw IllegalArgumentException(
                    "Malformed VarInt data: byte buffer was truncated in the middle of a VarInt sequence.",
                )
                -2L -> throw IllegalArgumentException(
                    "Incomplete VarInt data: expected $expectedSize elements, but buffer contained fewer VarInt values.",
                )
                else -> throw IllegalArgumentException(
                    "Failed to decode VarInt data: native decoder returned error code $result.",
                )
            }
        }

        return result
    }

    fun encodeShortsVarInt(
        sourceSlice: MemorySegment,
        sourceLength: Long,
        destinationSlice: MemorySegment,
    ): Long {
        return encodeMethodHandle.invokeExact(
            sourceSlice,
            sourceLength,
            destinationSlice,
        ) as Long
    }

    fun calculateVarIntEncodedSize(
        sourceSlice: MemorySegment,
        sourceLength: Long,
    ): Long {
        return sizeMethodHandle.invokeExact(
            sourceSlice,
            sourceLength,
        ) as Long
    }

    fun countVarIntTerminals(
        sourceSlice: MemorySegment,
        sourceLength: Long = sourceSlice.byteSize(),
    ): Long {
        return countTerminalsMethodHandle.invokeExact(
            sourceSlice,
            sourceLength,
        ) as Long
    }

    private fun getPlatformName(): String {
        val operatingSystemName = System.getProperty("os.name").lowercase()

        return when {
            operatingSystemName.contains("win") -> "windows"
            operatingSystemName.contains("mac") || operatingSystemName.contains("darwin") -> "macos"
            operatingSystemName.contains("nix") || operatingSystemName.contains("nux") || operatingSystemName.contains("aix") -> "linux"
            else -> throw UnsupportedOperationException("Unsupported OS: $operatingSystemName")
        }
    }

    private fun getArchName(): String {
        return when (val architectureName = System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw UnsupportedOperationException("Unsupported Architecture: $architectureName")
        }
    }

    private fun getLibraryFileName(platform: String): String {
        return when (platform) {
            "windows" -> "totem_sys.dll"
            "macos" -> "libtotem_sys.dylib"
            "linux" -> "libtotem_sys.so"
            else -> throw IllegalArgumentException("Unsupported platform mapping: $platform")
        }
    }

    private fun loadNativeLibrary(): SymbolLookup {
        val operatingSystem = getPlatformName()
        val cpuArchitecture = getArchName()
        val libraryName = getLibraryFileName(operatingSystem)

        // try local development build path first
        val localDevelopmentPath = Path.of("native/target/release", libraryName).toAbsolutePath()

        if (Files.exists(localDevelopmentPath)) {
            try {
                System.load(localDevelopmentPath.toString())
                return SymbolLookup.libraryLookup(localDevelopmentPath, Arena.global())
            } catch (_: Throwable) {
                // fallback to classpath resource loader
            }
        }

        val resourcePath = "/natives/${operatingSystem}_${cpuArchitecture}/$libraryName"
        val resourceUrl = TotemSys::class.java.getResource(resourcePath)
            ?: throw UnsatisfiedLinkError("Native library $libraryName not found in resources at $resourcePath")

        val temporaryDirectory = Files.createTempDirectory("totem_sys_natives")
        val temporaryFile = temporaryDirectory.resolve(libraryName)
        temporaryFile.toFile().deleteOnExit()

        resourceUrl.openStream().use { resourceStream ->
            Files.copy(resourceStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING)
        }

        System.load(temporaryFile.toAbsolutePath().toString())

        return SymbolLookup.libraryLookup(temporaryFile, Arena.global())
    }

}
