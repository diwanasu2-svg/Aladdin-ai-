package com.aladdin.performance.threading

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Four dedicated thread pools, each tuned for its workload:
 *
 *  AUDIO   — 2 threads, THREAD_PRIORITY_URGENT_AUDIO (-16)
 *  ML      — 2 threads, THREAD_PRIORITY_DEFAULT (0)
 *  NETWORK — 4 threads, THREAD_PRIORITY_BACKGROUND (10)
 *  UI      — delegates to main thread (Dispatchers.Main)
 */
object ThreadPoolManager {

    private const val TAG = "ThreadPoolManager"

    val audio: ExecutorCoroutineDispatcher = buildPool(
        name       = "aladdin-audio",
        coreSize   = 2,
        maxSize    = 2,
        androidPriority = Process.THREAD_PRIORITY_URGENT_AUDIO,
        keepAliveMs = 0L
    ).asCoroutineDispatcher()

    val ml: ExecutorCoroutineDispatcher = buildPool(
        name       = "aladdin-ml",
        coreSize   = 2,
        maxSize    = 4,
        androidPriority = Process.THREAD_PRIORITY_DEFAULT,
        keepAliveMs = 30_000L
    ).asCoroutineDispatcher()

    val network: ExecutorCoroutineDispatcher = buildPool(
        name       = "aladdin-net",
        coreSize   = 4,
        maxSize    = 8,
        androidPriority = Process.THREAD_PRIORITY_BACKGROUND,
        keepAliveMs = 60_000L
    ).asCoroutineDispatcher()

    fun shutdown() {
        Log.i(TAG, "Shutting down thread pools")
        (audio.executor as? ExecutorService)?.shutdown()
        (ml.executor as? ExecutorService)?.shutdown()
        (network.executor as? ExecutorService)?.shutdown()
    }

    private fun buildPool(
        name: String, coreSize: Int, maxSize: Int,
        androidPriority: Int, keepAliveMs: Long
    ): ThreadPoolExecutor {
        val counter = AtomicInteger(0)
        val factory = ThreadFactory { r ->
            Thread {
                Process.setThreadPriority(androidPriority)
                r.run()
            }.apply {
                this.name = "$name-${counter.incrementAndGet()}"
                isDaemon = true
            }
        }
        return ThreadPoolExecutor(
            coreSize, maxSize, keepAliveMs, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(64), factory,
            ThreadPoolExecutor.CallerRunsPolicy()  // backpressure: caller executes if queue full
        ).also { Log.d(TAG, "Pool '$name' created (core=$coreSize max=$maxSize prio=$androidPriority)") }
    }
}
