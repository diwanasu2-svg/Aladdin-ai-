package com.aladdin.performance.async

import android.util.Log
import com.aladdin.performance.threading.ThreadPoolManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-blocking dispatcher for all Aladdin I/O tasks.
 * Ensures the UI thread is NEVER blocked:
 *  - Audio capture → ThreadPoolManager.audio
 *  - ML inference  → ThreadPoolManager.ml
 *  - Network calls → ThreadPoolManager.network
 *  - DB / disk     → Dispatchers.IO
 */
class AsyncProcessor {

    companion object { private const val TAG = "AsyncProcessor" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = AtomicInteger(0)

    // ─── Audio ────────────────────────────────────────────────────────────────
    fun <T> audio(block: suspend CoroutineScope.() -> T): Deferred<T> =
        tracked { scope.async(ThreadPoolManager.audio, block = block) }

    // ─── ML Inference ─────────────────────────────────────────────────────────
    fun <T> ml(block: suspend CoroutineScope.() -> T): Deferred<T> =
        tracked { scope.async(ThreadPoolManager.ml, block = block) }

    // ─── Network ──────────────────────────────────────────────────────────────
    fun <T> network(block: suspend CoroutineScope.() -> T): Deferred<T> =
        tracked { scope.async(ThreadPoolManager.network, block = block) }

    // ─── Disk / DB ────────────────────────────────────────────────────────────
    fun <T> io(block: suspend CoroutineScope.() -> T): Deferred<T> =
        tracked { scope.async(Dispatchers.IO, block = block) }

    // ─── Parallel Fan-out ─────────────────────────────────────────────────────
    /**
     * Run multiple async tasks in parallel and await all results.
     * Throws [AggregateException] if any task fails.
     */
    suspend fun <T> parallel(vararg tasks: suspend () -> T): List<T> = coroutineScope {
        tasks.map { async(Dispatchers.Default) { it() } }.awaitAll()
    }

    /**
     * Produce a Flow that runs [producer] on the correct dispatcher and
     * emits results without blocking the collector.
     */
    fun <T> flowOn(dispatcher: CoroutineDispatcher = Dispatchers.Default, producer: suspend FlowCollector<T>.() -> Unit): Flow<T> =
        flow(producer).flowOn(dispatcher)

    fun activeJobCount() = activeJobs.get()

    fun cancelAll() { scope.cancel() }

    private fun <T> tracked(factory: () -> Deferred<T>): Deferred<T> {
        activeJobs.incrementAndGet()
        return factory().also { deferred ->
            deferred.invokeOnCompletion { activeJobs.decrementAndGet() }
        }
    }
}
