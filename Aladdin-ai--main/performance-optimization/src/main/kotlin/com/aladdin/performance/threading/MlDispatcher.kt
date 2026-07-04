package com.aladdin.performance.threading

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/**
 * Runs ML inference on dedicated threads (normal priority).
 * Inference calls are automatically serialised per model via a Mutex.
 */
class MlDispatcher {

    private val scope = CoroutineScope(SupervisorJob() + ThreadPoolManager.ml)
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** Run an inference task. Serialised to prevent concurrent ONNX/GGUF access. */
    suspend fun <T> runInference(block: suspend () -> T): T = withContext(ThreadPoolManager.ml) {
        mutex.withLock { block() }
    }

    /** Run a non-serialised ML task (e.g. post-processing, embedding lookup) */
    fun <T> runAsync(block: suspend CoroutineScope.() -> T): Deferred<T> =
        scope.async(ThreadPoolManager.ml, block = block)

    fun cancel() = scope.cancel()
}
