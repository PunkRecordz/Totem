package org.punkrecordz.totem.anvil

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// hosts the JVM side of pipelined native work while bounding concurrent worker threads
internal object AnvilNativeExecutor {

    private const val KEEP_ALIVE_SECONDS: Long = 30L

    private val threadSequence: AtomicInteger = AtomicInteger(1)

    val executor: ExecutorService by lazy {
        val maxWorkers = maxOf(2, Runtime.getRuntime().availableProcessors())
        ThreadPoolExecutor(
            maxWorkers,
            maxWorkers,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { runnable ->
            Thread(runnable, "totem-anvil-native-${threadSequence.getAndIncrement()}").also { workerThread ->
                workerThread.isDaemon = true
            }
        }.also { pool ->
            pool.allowCoreThreadTimeOut(true)
        }
    }

    // native workers read and write pooled buffers directly, so callers must wait until completion even if interrupted
    fun awaitCompletion(
        task: Future<*>,
        rethrowFailure: Boolean,
    ) {
        var interrupted = false
        var recordedFailure: Throwable? = null

        while (true) {
            try {
                task.get()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (executionException: ExecutionException) {
                recordedFailure = executionException.cause ?: executionException
                break
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt()
        }

        if (rethrowFailure && recordedFailure != null) {
            throw recordedFailure
        }
    }

}
