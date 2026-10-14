package org.punkrecordz.totem.concurrent

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
