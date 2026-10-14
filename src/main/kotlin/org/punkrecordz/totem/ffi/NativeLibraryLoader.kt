package org.punkrecordz.totem.ffi

import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

internal object NativeLibraryLoader {

    val symbolLookup: SymbolLookup by lazy {
        loadNativeLibrary()
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

        val localDevelopmentPath = Path.of("native/target/release", libraryName).toAbsolutePath()
        val resourcePath = "/natives/${operatingSystem}_${cpuArchitecture}/$libraryName"

        // load binary bytes from local cargo target or embedded jar resources
        val libraryBytes = if (Files.exists(localDevelopmentPath)) {
            Files.readAllBytes(localDevelopmentPath)
        } else {
            val resourceUrl = TotemSys::class.java.getResource(resourcePath)
                ?: throw UnsatisfiedLinkError("Native library $libraryName not found in resources at $resourcePath")

            resourceUrl.openStream().use { resourceStream ->
                resourceStream.readAllBytes()
            }
        }

        // compute content hash to isolate versions in temporary directory
        val digest = MessageDigest.getInstance("SHA-256").digest(libraryBytes)
        val contentHash = HexFormat.of().formatHex(digest, 0, 8)

        val cacheDirectory = Path.of(System.getProperty("java.io.tmpdir"), "totem-natives")
        Files.createDirectories(cacheDirectory)

        val cachedLibraryFile = cacheDirectory.resolve("${contentHash}_$libraryName")
        if (!Files.exists(cachedLibraryFile) || Files.size(cachedLibraryFile) != libraryBytes.size.toLong()) {
            val stagingFile = Files.createTempFile(cacheDirectory, "staging_", ".tmp")
            try {
                Files.write(stagingFile, libraryBytes)
                Files.move(
                    stagingFile,
                    cachedLibraryFile,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.deleteIfExists(stagingFile)
            }
        }

        System.load(cachedLibraryFile.toAbsolutePath().toString())

        return SymbolLookup.libraryLookup(cachedLibraryFile, Arena.global())
    }

}
