package org.punkrecordz.totem.tool

import org.punkrecordz.totem.Totem
import org.punkrecordz.totem.io.compression.CompressionType
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * A generator tool to create large synthetic Sponge Schematic files.
 *
 * Strategy:
 * Generates a standard NBT schematic structure containing a uniform block palette
 * and a VarInt-encoded byte array representing the blocks. This enables testing
 * high-throughput compression and serialization with zero copyright concerns and
 * full reproducibility.
 */
fun main(arguments: Array<String>) {
    val sizeString = arguments.firstOrNull() ?: "200"
    val size = sizeString.toIntOrNull() ?: throw IllegalArgumentException(
        "Invalid size argument: $sizeString. Please provide a valid integer representing the cube side length."
    )

    val outputFileString = if (arguments.size > 1) {
        arguments[1]
    } else {
        "src/test/resources/synthetic_${size}x${size}x${size}.schem"
    }

    val outputPath = Path(outputFileString)

    println("Generating synthetic Sponge Schematic of size ${size}x${size}x${size} (${size * size * size} blocks)...")

    val width = size
    val height = size
    val length = size
    val volume = width * height * length

    // Generate a pseudo-random sequence of block IDs using a high-quality 64-bit LCG.
    // This provides realistic high-entropy data, simulating a complex world build.
    val blockData = ByteArray(volume)
    var seed = 12345L
    for (index in 0 until volume) {
        seed = seed * 2862933555777941757L + 3037000493L
        blockData[index] = ((seed ushr 56) and 127L).toByte()
    }

    outputPath.parent?.createDirectories()

    // Write the schematic using Totem's GZIP writer
    Totem.write(
        path = outputPath,
        compression = CompressionType.GZIP,
    ) {
        compound("Schematic") {
            short("Width", width.toShort())
            short("Height", height.toShort())
            short("Length", length.toShort())
            compound("Blocks") {
                byteArray("Data", blockData)
            }
        }
    }

    val fileSizeMegaBytes = outputPath.toFile().length().toDouble() / (1024.0 * 1024.0)

    println("Successfully generated schematic: ${outputPath.toAbsolutePath()}")
    println("File size on disk: ${String.format("%.2f", fileSizeMegaBytes)} MB")
}
