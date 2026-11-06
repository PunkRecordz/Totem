package org.punkrecordz.totem.bench

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

fun main() {
    val regionCount = System.getProperty("totem.bench.regions", "16").toInt()
    val threadCount = System.getProperty(
        "totem.bench.threads",
        Runtime.getRuntime().availableProcessors().toString(),
    ).toInt()
    val rounds = System.getProperty("totem.bench.rounds", "3").toInt()
    val scenarioFilter = System.getProperty("totem.bench.filter", "")
    val workingDirectory = Path.of(System.getProperty("totem.bench.dir", "build/anvil-bench"))

    Files.createDirectories(workingDirectory)

    AnvilWorkload(workingDirectory, regionCount).use { workload ->
        workload.writeSourceWorld()

        val summary = StringBuilder().also { builder ->
            builder.append("regions=$regionCount chunks=${workload.totalChunks} threads=$threadCount rounds=$rounds\n")
            builder.append(
                "avg raw chunk=${workload.averageRawChunkBytes} B, " +
                    "source world=${workload.totalSourceBytes() / 1_048_576} MiB\n",
            )
        }.toString()
        println(summary)

        val scenarios = AnvilBenchmarkScenarios(workload, threadCount)
        val selectedScenarios = scenarios.register().filter { scenario -> scenario.name.contains(scenarioFilter) }

        for (result in scenarios.run(selectedScenarios, rounds)) {
            println(
                String.format(
                    Locale.ROOT,
                    "%-34s %9.1f ms  %11.0f chunks/s  %7.2f GB/s raw",
                    result.name,
                    result.bestNanos / 1e6,
                    result.chunksPerSecond,
                    result.rawGigabytesPerSecond,
                ),
            )
        }

        println("checksum=${scenarios.consumedChecksum()}")
    }
}
