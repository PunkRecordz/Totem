package org.punkrecordz.totem.concurrent

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

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

internal fun <T> Future<T>.awaitResult(): T {
    return try {
        get()
    } catch (exception: ExecutionException) {
        throw exception.cause ?: exception
    }
}

