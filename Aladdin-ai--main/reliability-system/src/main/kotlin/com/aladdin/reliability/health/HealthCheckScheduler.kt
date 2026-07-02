package com.aladdin.reliability.health

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Runs health checks every 5 minutes and notifies listeners.
 */
class HealthCheckScheduler(private val context: Context) {

    companion object {
        private const val TAG = "HealthCheckScheduler"
        const val INTERVAL_MS = 5 * 60 * 1_000L
    }

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checker = HealthChecker(context)
    private val listeners = mutableListOf<(HealthReport) -> Unit>()
    private var running = false

    fun addListener(listener: (HealthReport) -> Unit) { listeners.add(listener) }

    fun start() {
        if (running) return
        running = true
        scope.launch {
            Log.i(TAG, "HealthCheckScheduler started (interval ${INTERVAL_MS / 60_000}min)")
            while (running) {
                runCatching {
                    val report = checker.runAll()
                    listeners.forEach { it(report) }
                }.onFailure { Log.e(TAG, "Health check failed", it) }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() { running = false; scope.cancel() }

    /** Run an immediate check outside the schedule */
    suspend fun runNow(): HealthReport = checker.runAll()
}
