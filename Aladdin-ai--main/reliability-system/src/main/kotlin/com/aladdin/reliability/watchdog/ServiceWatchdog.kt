package com.aladdin.reliability.watchdog

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*

/**
 * Monitors a list of critical services and restarts them if they die.
 * Uses exponential backoff: 5s → 10s → 30s → 1m → 5m.
 */
class ServiceWatchdog(private val context: Context) {

    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val CHECK_INTERVAL_MS = 15_000L
    }

    data class WatchedService(
        val name: String,
        val serviceClass: Class<*>,
        val intentExtras: Map<String, String> = emptyMap()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watched = mutableListOf<WatchedService>()
    private val restartAttempts = mutableMapOf<String, Int>()
    private var running = false

    fun watch(service: WatchedService) {
        watched.add(service)
        Log.i(TAG, "Now watching service: ${service.name}")
    }

    fun start() {
        if (running) return
        running = true
        scope.launch {
            Log.i(TAG, "ServiceWatchdog started, checking every ${CHECK_INTERVAL_MS / 1000}s")
            while (running) {
                delay(CHECK_INTERVAL_MS)
                watched.forEach { checkAndRestart(it) }
            }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        Log.i(TAG, "ServiceWatchdog stopped")
    }

    fun resetBackoff(serviceName: String) {
        restartAttempts[serviceName] = 0
    }

    private suspend fun checkAndRestart(service: WatchedService) {
        if (isServiceRunning(service.serviceClass)) {
            restartAttempts[service.name] = 0
            return
        }

        val attempt = restartAttempts.getOrDefault(service.name, 0)
        val delayMs = BackoffStrategy.delayMs(attempt)
        Log.w(TAG, "Service '${service.name}' is DEAD (attempt #${attempt + 1}), restarting in ${delayMs / 1000}s")

        delay(delayMs)

        if (!isServiceRunning(service.serviceClass)) {
            restartAttempts[service.name] = attempt + 1
            try {
                val intent = Intent(context, service.serviceClass).apply {
                    service.intentExtras.forEach { (k, v) -> putExtra(k, v) }
                }
                context.startForegroundService(intent)
                Log.i(TAG, "Restarted service '${service.name}'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart '${service.name}'", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name
        }
    }
}
