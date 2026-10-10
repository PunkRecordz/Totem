package org.punkrecordz.totem

import com.sun.management.OperatingSystemMXBean
import org.junit.jupiter.api.Test
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.Tags
import org.punkrecordz.totem.view.replaceVarIntInPlace
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.stream.NativeByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * End-to-end performance and resource measurement suite on the 1-Billion blocks schematic.
 *
 * Strategy:
 * Measures wall-clock time, accumulated process CPU time, JVM heap consumption, and off-heap FFM
 * allocation during full end-to-end transformation pipelines on real-world massive schematics.
 *
 * Performance targets:
 * Sub-3s end-to-end processing of 1 billion blocks with under 30MB JVM heap overhead.
 */
class MeasureHugeSchemTest {

    private val schematicPath: Path = Path.of("D:/IdeaProjects/ItsMyConvert/debug/schematic/small-schem-to-test.schem")
    private val tempOutputPath: Path = Path.of("temp_huge_output.schem")

    @Test
    fun measure() {
        val operatingSystemBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val runtime = Runtime.getRuntime()

        println("=================================================================")
        println("BENCHMARK TOTEM SUR LE SCHEMATIC DE 1 MILLIARD DE BLOCS (981 Mo)")
        println("Fichier : small-schem-to-test.schem (18 Mo compresse / ~1 Go decompresse)")
        println("=================================================================")

        // GC initial to stabilize heap measurement
        System.gc()
        Thread.sleep(100)
        val initialHeapUsed = runtime.totalMemory() - runtime.freeMemory()

        val wallStart = System.nanoTime()
        val cpuStart = operatingSystemBean.processCpuTime

        Arena.ofConfined().use { arena ->
            // 1. LOAD & DECOMPRESS
            val loadStart = System.nanoTime()
            val cpuLoadStart = operatingSystemBean.processCpuTime
            val (name, root) = Totem.load(schematicPath, arena)
            val loadEnd = System.nanoTime()
            val cpuLoadEnd = operatingSystemBean.processCpuTime

            val schematic = if (root.containsKey("Width")) root else root.getCompound("Schematic") ?: root
            val width = schematic.getShort("Width")!!.toInt()
            val height = schematic.getShort("Height")!!.toInt()
            val length = schematic.getShort("Length")!!.toInt()
            val volume = width * height * length

            val dataTag = if (schematic.containsKey("BlockData")) {
                schematic.getByteArray("BlockData")!!
            } else {
                schematic.getCompound("Blocks")!!.getByteArray("Data")!!
            }

            println("--- 1. CHARGEMENT & DECOMPRESSION GZIP ---")
            println("    Volume total              : $volume blocs")
            println("    Taille BlockData brut     : ${dataTag.size} octets (${dataTag.size / (1024 * 1024)} Mo)")
            val loadWallMs = (loadEnd - loadStart) / 1_000_000.0
            val loadCpuMs = (cpuLoadEnd - cpuLoadStart) / 1_000_000.0
            println(String.format(java.util.Locale.US, "    Temps ecoule (Wall-clock) : %.2f ms (%.2f s)", loadWallMs, loadWallMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Temps CPU consomme        : %.2f ms (%.2f s)", loadCpuMs, loadCpuMs / 1000.0))

            // 2. DECODE VARINT VIA FUSED PARALLEL SIMD
            val decodeStart = System.nanoTime()
            val cpuDecodeStart = operatingSystemBean.processCpuTime
            val blocks = dataTag.toVarIntShortArray(volume, arena)
            val decodeEnd = System.nanoTime()
            val cpuDecodeEnd = operatingSystemBean.processCpuTime

            val decodeWallMs = (decodeEnd - decodeStart) / 1_000_000.0
            val decodeCpuMs = (cpuDecodeEnd - cpuDecodeStart) / 1_000_000.0
            val decodeRate = (volume / 1_000_000.0) / (decodeWallMs / 1000.0)
            println("--- 2. DECODAGE VARINT FUSED SIMD ($volume BLOCS -> SHORTS) ---")
            println(String.format(java.util.Locale.US, "    Temps ecoule (Wall-clock) : %.2f ms (%.2f s)", decodeWallMs, decodeWallMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Temps CPU consomme        : %.2f ms (%.2f s)", decodeCpuMs, decodeCpuMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Debit de decodage         : %.2f millions de blocs/sec", decodeRate))

            // 3. TRANSLATION LUT (DDR MEMORY BUS CEILING)
            val translationTable = ShortArray(65536) { index -> index.toShort() }
            translationTable[0] = 1.toShort() // translate air to stone
            translationTable[2] = 1.toShort() // translate grass to stone

            val translateStart = System.nanoTime()
            val cpuTranslateStart = operatingSystemBean.processCpuTime
            blocks.translate(translationTable)
            val translateEnd = System.nanoTime()
            val cpuTranslateEnd = operatingSystemBean.processCpuTime

            val translateWallMs = (translateEnd - translateStart) / 1_000_000.0
            val translateCpuMs = (cpuTranslateEnd - cpuTranslateStart) / 1_000_000.0
            val translateRate = (volume / 1_000_000.0) / (translateWallMs / 1000.0)
            println("--- 3. TRANSLATION NATIVE LUT (1 MILLIARD DE BLOCS) ---")
            println(String.format(java.util.Locale.US, "    Temps ecoule (Wall-clock) : %.2f ms (%.2f s)", translateWallMs, translateWallMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Temps CPU consomme        : %.2f ms (%.2f s)", translateCpuMs, translateCpuMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Debit de translation      : %.2f millions de blocs/sec", translateRate))

            // 4. ENCODE VARINT PARALLEL AVX2
            val encodeStart = System.nanoTime()
            val cpuEncodeStart = operatingSystemBean.processCpuTime
            val modifiedData = blocks.toVarIntByteArray(arena)
            val encodeEnd = System.nanoTime()
            val cpuEncodeEnd = operatingSystemBean.processCpuTime

            val encodeWallMs = (encodeEnd - encodeStart) / 1_000_000.0
            val encodeCpuMs = (cpuEncodeEnd - cpuEncodeStart) / 1_000_000.0
            val encodeRate = (volume / 1_000_000.0) / (encodeWallMs / 1000.0)
            println("--- 4. ENCODAGE VARINT PARALLELE ($volume SHORTS -> OCTETS) ---")
            println("    Taille encodee            : ${modifiedData.size} octets (${modifiedData.size / (1024 * 1024)} Mo)")
            println(String.format(java.util.Locale.US, "    Temps ecoule (Wall-clock) : %.2f ms (%.2f s)", encodeWallMs, encodeWallMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Temps CPU consomme        : %.2f ms (%.2f s)", encodeCpuMs, encodeCpuMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Debit d'encodage          : %.2f millions de blocs/sec", encodeRate))

            // 5. SAVE & GZIP COMPRESSION
            val newRoot = Tags.compound().apply {
                for ((key, value) in root) {
                    if (key == "BlockData") {
                        put(key, modifiedData)
                    } else {
                        put(key, value)
                    }
                }
            }

            val saveStart = System.nanoTime()
            val cpuSaveStart = operatingSystemBean.processCpuTime
            Totem.save(name, newRoot, tempOutputPath, CompressionType.GZIP)
            val saveEnd = System.nanoTime()
            val cpuSaveEnd = operatingSystemBean.processCpuTime

            val saveWallMs = (saveEnd - saveStart) / 1_000_000.0
            val saveCpuMs = (cpuSaveEnd - cpuSaveStart) / 1_000_000.0
            println("--- 5. SAUVEGARDE & COMPRESSION GZIP MULTI-MEMBER ---")
            println("    Taille fichier produit    : ${tempOutputPath.toFile().length()} octets (${tempOutputPath.toFile().length() / (1024 * 1024)} Mo)")
            println(String.format(java.util.Locale.US, "    Temps ecoule (Wall-clock) : %.2f ms (%.2f s)", saveWallMs, saveWallMs / 1000.0))
            println(String.format(java.util.Locale.US, "    Temps CPU consomme        : %.2f ms (%.2f s)", saveCpuMs, saveCpuMs / 1000.0))

            val wallTotalMs = (System.nanoTime() - wallStart) / 1_000_000.0
            val cpuTotalMs = (operatingSystemBean.processCpuTime - cpuStart) / 1_000_000.0
            val finalHeapUsed = runtime.totalMemory() - runtime.freeMemory()
            val heapDeltaMb = (finalHeapUsed - initialHeapUsed) / (1024.0 * 1024.0)

            val committedTotalMb = operatingSystemBean.committedVirtualMemorySize / (1024.0 * 1024.0)

            println("=================================================================")
            println("BILAN GLOBAL PIPELINE COMPLET (1 MILLIARD DE BLOCS)")
            println("=================================================================")
            println(String.format(java.util.Locale.US, "Temps Total (Wall-clock)  : %.2f ms (%.2f secondes)", wallTotalMs, wallTotalMs / 1000.0))
            println(String.format(java.util.Locale.US, "Temps CPU Total           : %.2f ms (%.2f secondes CPU)", cpuTotalMs, cpuTotalMs / 1000.0))
            println(String.format(java.util.Locale.US, "Consommation Heap JVM     : %.2f Mo", heapDeltaMb))
            println(String.format(java.util.Locale.US, "Memoire Totale (Process)  : %.2f Go (Committed Virtual = Heap + Off-Heap)", committedTotalMb / 1024.0))
            println("=================================================================")

            tempOutputPath.deleteIfExists()
        }
    }

    @Test
    fun measureInPlace() {
        val operatingSystemBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val runtime = Runtime.getRuntime()

        println("=================================================================")
        println("BENCHMARK TOTEM IN-PLACE SUR LE SCHEMATIC DE 1 MILLIARD DE BLOCS")
        println("=================================================================")

        System.gc()
        Thread.sleep(100)
        val initialHeapUsed = runtime.totalMemory() - runtime.freeMemory()

        val wallStart = System.nanoTime()
        val cpuStart = operatingSystemBean.processCpuTime

        Arena.ofConfined().use { arena ->
            // 1. LOAD & DECOMPRESS
            val loadStart = System.nanoTime()
            val (name, root) = Totem.load(schematicPath, arena)
            val loadEnd = System.nanoTime()

            val schematic = if (root.containsKey("Width")) root else root.getCompound("Schematic") ?: root
            val dataTag = if (schematic.containsKey("BlockData")) {
                schematic.getByteArray("BlockData")!!
            } else {
                schematic.getCompound("Blocks")!!.getByteArray("Data")!!
            }

            val loadWallMs = (loadEnd - loadStart) / 1_000_000.0
            println(String.format(java.util.Locale.US, "--- 1. CHARGEMENT & DECOMPRESSION GZIP : %.2f ms", loadWallMs))

            // 2. IN-PLACE SIMD REPLACEMENT DIRECTEMENT DANS LE STREAM VARINT (ZERO ALLOCATION)
            val inPlaceStart = System.nanoTime()
            val replaced = dataTag.replaceVarIntInPlace(0.toShort(), 1.toShort())
            val inPlaceEnd = System.nanoTime()

            val inPlaceWallMs = (inPlaceEnd - inPlaceStart) / 1_000_000.0
            println(String.format(java.util.Locale.US, "--- 2. REMPLACEMENT IN-PLACE AVX2 (1 Go stream) : %.2f ms (success: %b)", inPlaceWallMs, replaced))

            // 3. ZERO-COPY NATIVE SAVE & GZIP COMPRESSION
            val saveStart = System.nanoTime()
            Totem.save(name, root, tempOutputPath, CompressionType.GZIP)
            val saveEnd = System.nanoTime()

            val saveWallMs = (saveEnd - saveStart) / 1_000_000.0
            println(String.format(java.util.Locale.US, "--- 3. SAUVEGARDE NATIVE ZERO-COPY GZIP : %.2f ms", saveWallMs))

            // 4. TEST DECHARGEMENT PARALLELE MULTI-MEMBRES SUR LE FICHIER PRODUIT PAR TOTEM (1 GO)
            Arena.ofConfined().use { reloadArena ->
                val reloadStart = System.nanoTime()
                val (reloadedName, reloadedRoot) = Totem.load(tempOutputPath, reloadArena)
                val reloadEnd = System.nanoTime()
                val reloadWallMs = (reloadEnd - reloadStart) / 1_000_000.0
                println(String.format(java.util.Locale.US, "--- 4. DECOMPRESSION PARALLELE MULTI-MEMBRES (1 Go) : %.2f ms (%.2f s)", reloadWallMs, reloadWallMs / 1000.0))
            }

            val wallTotalMs = (System.nanoTime() - wallStart) / 1_000_000.0
            val cpuTotalMs = (operatingSystemBean.processCpuTime - cpuStart) / 1_000_000.0
            val finalHeapUsed = runtime.totalMemory() - runtime.freeMemory()
            val heapDeltaMb = (finalHeapUsed - initialHeapUsed) / (1024.0 * 1024.0)

            val committedTotalMb = operatingSystemBean.committedVirtualMemorySize / (1024.0 * 1024.0)

            println("=================================================================")
            println("BILAN GLOBAL PIPELINE IN-PLACE (1 MILLIARD DE BLOCS)")
            println("=================================================================")
            println(String.format(java.util.Locale.US, "Temps Total (Wall-clock)  : %.2f ms (%.2f secondes)", wallTotalMs, wallTotalMs / 1000.0))
            println(String.format(java.util.Locale.US, "Temps CPU Total           : %.2f ms (%.2f secondes CPU)", cpuTotalMs, cpuTotalMs / 1000.0))
            println(String.format(java.util.Locale.US, "Consommation Heap JVM     : %.2f Mo", heapDeltaMb))
            println(String.format(java.util.Locale.US, "Memoire Totale (Process)  : %.2f Go (Committed Virtual = Heap + Off-Heap)", committedTotalMb / 1024.0))
            println("=================================================================")

            tempOutputPath.deleteIfExists()
        }
    }

    @Test
    fun measureArraySerialization() {
        println("=================================================================")
        println("BENCHMARK VECTORISATION TABLEAUX PRIMITIFS DANS TOTEM")
        println("=================================================================")

        val elementCount = 25_000_000
        val intArray = IntArray(elementCount) { index -> index * 3 + 1 }
        val longArray = LongArray(elementCount) { index -> index.toLong() * 7L + 3L }

        Arena.ofConfined().use { arena ->
            // Warm-up
            val warmStream = NativeByteArrayOutputStream(arena)
            warmStream.writeIntArray(intArray)
            warmStream.writeLongArray(longArray)

            // Benchmark writeIntArray (100 MB)
            val intStart = System.nanoTime()
            val intStream = NativeByteArrayOutputStream(arena)
            intStream.writeIntArray(intArray)
            val intEnd = System.nanoTime()
            val intWallMs = (intEnd - intStart) / 1_000_000.0
            val intThroughput = (elementCount * 4L / (1024.0 * 1024.0)) / (intWallMs / 1000.0)

            println(
                String.format(
                    java.util.Locale.US,
                    "Vectorise IntArray (25M ints = 100 Mo)   : %.2f ms (%.2f Mo/s)",
                    intWallMs,
                    intThroughput,
                ),
            )

            // Benchmark writeLongArray (200 MB)
            val longStart = System.nanoTime()
            val longStream = NativeByteArrayOutputStream(arena)
            longStream.writeLongArray(longArray)
            val longEnd = System.nanoTime()
            val longWallMs = (longEnd - longStart) / 1_000_000.0
            val longThroughput = (elementCount * 8L / (1024.0 * 1024.0)) / (longWallMs / 1000.0)

            println(
                String.format(
                    java.util.Locale.US,
                    "Vectorise LongArray (25M longs = 200 Mo) : %.2f ms (%.2f Mo/s)",
                    longWallMs,
                    longThroughput,
                ),
            )

            // Comparison with legacy scalar element-by-element loop
            val scalarSegment = arena.allocateUninitialized(elementCount * 8L)

            val scalarIntStart = System.nanoTime()
            for (index in 0 until elementCount) {
                scalarSegment.set(MemoryLayouts.INT, index * 4L, intArray[index])
            }
            val scalarIntEnd = System.nanoTime()
            val scalarIntWallMs = (scalarIntEnd - scalarIntStart) / 1_000_000.0
            val scalarIntThroughput = (elementCount * 4L / (1024.0 * 1024.0)) / (scalarIntWallMs / 1000.0)

            println(
                String.format(
                    java.util.Locale.US,
                    "Scalaire  IntArray (25M ints = 100 Mo)   : %.2f ms (%.2f Mo/s)",
                    scalarIntWallMs,
                    scalarIntThroughput,
                ),
            )

            val scalarLongStart = System.nanoTime()
            for (index in 0 until elementCount) {
                scalarSegment.set(MemoryLayouts.LONG, index * 8L, longArray[index])
            }
            val scalarLongEnd = System.nanoTime()
            val scalarLongWallMs = (scalarLongEnd - scalarLongStart) / 1_000_000.0
            val scalarLongThroughput = (elementCount * 8L / (1024.0 * 1024.0)) / (scalarLongWallMs / 1000.0)

            println(
                String.format(
                    java.util.Locale.US,
                    "Scalaire  LongArray (25M longs = 200 Mo) : %.2f ms (%.2f Mo/s)",
                    scalarLongWallMs,
                    scalarLongThroughput,
                ),
            )
            println("=================================================================")
        }
    }

}
