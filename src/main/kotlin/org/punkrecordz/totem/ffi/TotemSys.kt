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

    private val decodeMethodHandle by lazy {
        val symbol = nativeLibrary.find("decode_varint_shorts")
            .orElseThrow { UnsatisfiedLinkError("Symbol decode_varint_shorts not found in totem-sys native library") }
        nativeLinker.downcallHandle(symbol, decodeDescriptor)
    }

    private val encodeMethodHandle by lazy {
        val symbol = nativeLibrary.find("encode_shorts_varint")
            .orElseThrow { UnsatisfiedLinkError("Symbol encode_shorts_varint not found in totem-sys native library") }
        nativeLinker.downcallHandle(symbol, encodeDescriptor)
    }

    fun decodeVarIntShorts(
        sourceSlice: MemorySegment,
        destinationSlice: MemorySegment,
        expectedSize: Int,
    ) {
        decodeMethodHandle.invoke(
            sourceSlice,
            sourceSlice.byteSize(),
            destinationSlice,
            expectedSize,
        )
    }

    fun encodeShortsVarInt(
        sourceSlice: MemorySegment,
        sourceLength: Long,
        destinationSlice: MemorySegment,
    ): Long {
        return encodeMethodHandle.invoke(
            sourceSlice,
            sourceLength,
            destinationSlice,
        ) as Long
    }

    // detect operating system name
    private fun getPlatformName(): String {
        val operatingSystemName = System.getProperty("os.name").lowercase()

        return when {
            operatingSystemName.contains("win") -> "windows"
            operatingSystemName.contains("mac") || operatingSystemName.contains("darwin") -> "macos"
            operatingSystemName.contains("nix") || operatingSystemName.contains("nux") || operatingSystemName.contains("aix") -> "linux"
            else -> throw UnsupportedOperationException("Unsupported OS: $operatingSystemName")
        }
    }

    // detect cpu architecture
    private fun getArchName(): String {
        val architectureName = System.getProperty("os.arch").lowercase()

        return when {
            architectureName == "amd64" || architectureName == "x86_64" || architectureName == "x64" -> "x86_64"
            architectureName == "aarch64" || architectureName == "arm64" -> "aarch64"
            else -> throw UnsupportedOperationException("Unsupported Architecture: $architectureName")
        }
    }

    // map platform name to standard dynamic library name
    private fun getLibraryFileName(platform: String): String {
        return when (platform) {
            "windows" -> "totem_sys.dll"
            "macos" -> "libtotem_sys.dylib"
            "linux" -> "libtotem_sys.so"
            else -> throw IllegalArgumentException("Unsupported platform mapping: $platform")
        }
    }

    // resolve and load the appropriate totem-sys library
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

        // extract and load library from packaged classpath natives directory
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
