package com.aladdin.performance.startup

import android.util.Log
import kotlinx.coroutines.*

/**
 * Splits service initialisation into two waves:
 *
 *  CRITICAL  — must finish before UI is shown (voice capture, AI engine)
 *  DEFERRED  — start in background after UI is ready (analytics, cloud sync, diagnostics)
 *
 * Each service provides a suspend lambda; this class sequences and times them.
 */
class LazyServiceInitializer {

    companion object { private const val TAG = "LazyServiceInitializer" }

    data class ServiceEntry(
        val name: String,
        val critical: Boolean,
        val init: suspend () -> Unit
    )

    private val services = mutableListOf<ServiceEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register(name: String, critical: Boolean = false, init: suspend () -> Unit) {
        services.add(ServiceEntry(name, critical, init))
    }

    /** Run critical services synchronously (call from Application.onCreate). */
    suspend fun initCritical() {
        val critical = services.filter { it.critical }
        Log.i(TAG, "Initialising ${critical.size} critical service(s)...")
        critical.forEach { svc ->
            val t0 = System.currentTimeMillis()
            runCatching { svc.init() }
                .onSuccess { Log.d(TAG, "  ✓ ${svc.name} in ${System.currentTimeMillis() - t0}ms") }
                .onFailure { Log.e(TAG, "  ✗ ${svc.name} FAILED", it) }
        }
    }

    /** Schedule deferred services to start after [delayMs] (default 1 s). */
    fun initDeferred(delayMs: Long = 1_000L) {
        val deferred = services.filter { !it.critical }
        Log.i(TAG, "Scheduling ${deferred.size} deferred service(s) in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            deferred.forEach { svc ->
                launch {
                    val t0 = System.currentTimeMillis()
                    runCatching { svc.init() }
                        .onSuccess { Log.d(TAG, "  ✓ [deferred] ${svc.name} in ${System.currentTimeMillis() - t0}ms") }
                        .onFailure { Log.e(TAG, "  ✗ [deferred] ${svc.name} FAILED", it) }
                }
            }
        }
    }

    fun cancel() = scope.cancel()
}
