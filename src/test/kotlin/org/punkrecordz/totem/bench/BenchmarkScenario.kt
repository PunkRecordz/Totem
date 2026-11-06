package org.punkrecordz.totem.bench

internal class BenchmarkScenario(
    val name: String,
    val parallel: Boolean,
    val regionAction: (Int) -> Unit,
)
