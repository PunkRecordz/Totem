package org.punkrecordz.totem.concurrent

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shared daemon thread pool for intensive off-heap parallel data transformations.
 *
 * Strategy:
 * Reuses a single fixed thread pool across parallel GZIP compression and VarInt SIMD processing.
 * Using daemon threads avoids blocking JVM shutdown while pre-allocating execution workers
 * to prevent thread creation latency during hot data pipelines.
 *
 * Performance targets:
 * Zero-allocation concurrency dispatch across available hardware threads.
 */
object TotemThreadPool {

    val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
        ) { runnable ->
            Thread(runnable).apply {
                name = "totem-worker-${threadId()}"
                isDaemon = true
            }
        }
    }

}
