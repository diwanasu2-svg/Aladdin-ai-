package com.aladdin.performance.threading

import kotlinx.coroutines.*

/**
 * Runs network I/O on background-priority threads (THREAD_PRIORITY_BACKGROUND).
 * Never blocks the UI or audio threads.
 */
class NetworkDispatcher {

    private val scope = CoroutineScope(SupervisorJob() + ThreadPoolManager.network)

    suspend fun <T> fetch(block: suspend () -> T): T = withContext(ThreadPoolManager.network) { block() }

    fun <T> fetchAsync(block: suspend CoroutineScope.() -> T): Deferred<T> =
        scope.async(ThreadPoolManager.network, block = block)

    fun cancel() = scope.cancel()
}
