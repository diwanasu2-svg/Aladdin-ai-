package com.aladdin.performance.cpu

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accumulates work items and processes them in batches to amortise per-call overhead.
 * Example: batch 20 embedding requests into a single inference call instead of 20 serial calls.
 */
class BatchProcessor<T, R>(
    private val name: String,
    private val maxBatchSize: Int = 20,
    private val maxWaitMs: Long = 50L,
    private val processor: suspend (List<T>) -> List<R>
) {
    companion object { private const val TAG = "BatchProcessor" }

    private data class Item<T, R>(val value: T, val deferred: CompletableDeferred<R>)

    private val queue   = ConcurrentLinkedQueue<Item<T, R>>()
    private val running = AtomicBoolean(false)
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun submit(item: T): R {
        val deferred = CompletableDeferred<R>()
        queue.add(Item(item, deferred))
        ensureRunning()
        return deferred.await()
    }

    private fun ensureRunning() {
        if (running.compareAndSet(false, true)) {
            scope.launch {
                while (queue.isNotEmpty()) {
                    delay(maxWaitMs)   // collect items up to maxWaitMs
                    val batch = mutableListOf<Item<T, R>>()
                    while (batch.size < maxBatchSize) {
                        val item = queue.poll() ?: break
                        batch.add(item)
                    }
                    if (batch.isEmpty()) { running.set(false); break }

                    Log.d(TAG, "$name: processing batch of ${batch.size}")
                    val t0 = System.currentTimeMillis()
                    runCatching {
                        val results = processor(batch.map { it.value })
                        results.forEachIndexed { i, r -> batch[i].deferred.complete(r) }
                    }.onFailure { ex ->
                        batch.forEach { it.deferred.completeExceptionally(ex) }
                        Log.e(TAG, "$name batch failed", ex)
                    }
                    Log.d(TAG, "$name: batch done in ${System.currentTimeMillis() - t0}ms")
                }
                running.set(false)
            }
        }
    }

    fun pendingCount() = queue.size
    fun cancel() { scope.cancel(); queue.forEach { it.deferred.cancel() }; queue.clear() }
}
