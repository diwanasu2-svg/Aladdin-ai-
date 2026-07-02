package com.aladdin.reliability.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aladdin.reliability.crash.CrashHandler
import com.aladdin.reliability.crash.CrashLogger
import com.aladdin.reliability.crash.CrashRecoveryManager
import com.aladdin.reliability.diagnostics.DiagnosticReport
import com.aladdin.reliability.diagnostics.DiagnosticsRunner
import com.aladdin.reliability.health.HealthCheckScheduler
import com.aladdin.reliability.health.HealthReport
import com.aladdin.reliability.logging.LogConfig
import com.aladdin.reliability.logging.RotatingLogManager
import com.aladdin.reliability.performance.PerformanceMonitor
import com.aladdin.reliability.performance.PerformanceReport
import com.aladdin.reliability.validation.StartupValidationReport
import com.aladdin.reliability.validation.StartupValidator
import com.aladdin.reliability.watchdog.ServiceWatchdog
import com.aladdin.reliability.watchdog.WatchdogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Top-level entry point for the Aladdin Reliability System.
 *
 * Usage:
 * ```kotlin
 * val reliability = ReliabilityManager(context, appVersion = BuildConfig.VERSION_NAME)
 * reliability.start(restartIntent = mainActivityIntent)
 * ```
 */
class ReliabilityManager(
    private val context: Context,
    private val appVersion: String,
    private val requiredPermissions: List<String> = emptyList(),
    private val logConfig: LogConfig = LogConfig()
) {
    companion object {
        private const val TAG = "ReliabilityManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val crashLogger      = CrashLogger(context)
    val recoveryManager  = CrashRecoveryManager(context)
    val logManager       = RotatingLogManager(context, logConfig)
    val healthScheduler  = HealthCheckScheduler(context)
    val diagnosticsRunner = DiagnosticsRunner(context)
    val performanceMonitor = PerformanceMonitor(context)
    val startupValidator   = StartupValidator(context, requiredPermissions)

    private val healthListeners = mutableListOf<(HealthReport) -> Unit>()

    // ─── Startup ──────────────────────────────────────────────────────────────

    /**
     * Call this from Application.onCreate() or your main service's onCreate().
     * @param restartIntent  Intent used to relaunch the app after a crash (usually MainActivity).
     * @param watchedServices  Optional list of service classes to monitor with the watchdog.
     */
    fun start(
        restartIntent: Intent? = null,
        watchedServices: List<ServiceWatchdog.WatchedService> = emptyList()
    ) {
        Log.i(TAG, "ReliabilityManager starting (v$appVersion)")

        // 1. Install crash handler
        CrashHandler.install(context, crashLogger, appVersion, restartIntent)

        // 2. Start rotating log capture
        logManager.start()

        // 3. Announce crash recovery if we're coming back from a crash
        if (recoveryManager.isRecovering()) {
            val attempts = recoveryManager.getRecoveryAttempts()
            Log.w(TAG, "Recovering from crash (attempt #$attempts)")
            logManager.write("[RECOVERY] Restarted after crash, attempt #$attempts")
        }

        // 4. Schedule health checks (every 5 min)
        healthListeners.forEach { healthScheduler.addListener(it) }
        healthScheduler.addListener { report ->
            if (!report.overallHealthy) {
                logManager.write("[HEALTH] DEGRADED: ${report.issues.joinToString()}")
            }
        }
        healthScheduler.start()

        // 5. Start performance monitor
        performanceMonitor.start()

        // 6. Start watchdog + WatchdogService
        if (watchedServices.isNotEmpty()) {
            WatchdogService.start(context)
        }

        // 7. Run startup validation in background
        scope.launch {
            val report = startupValidator.validate()
            if (report.hasFatal) {
                Log.e(TAG, "STARTUP VALIDATION FAILED:\n${report.summary()}")
                logManager.write("[STARTUP] FATAL validation failures — see logcat")
            } else {
                Log.i(TAG, "Startup validation passed")
            }
        }

        Log.i(TAG, "ReliabilityManager started")
    }

    fun stop() {
        healthScheduler.stop()
        val perfReport = performanceMonitor.stop()
        logManager.stop()
        WatchdogService.stop(context)
        Log.i(TAG, "ReliabilityManager stopped")
    }

    // ─── Health ───────────────────────────────────────────────────────────────

    fun addHealthListener(listener: (HealthReport) -> Unit) {
        healthListeners.add(listener)
        healthScheduler.addListener(listener)
    }

    suspend fun runHealthCheckNow(): HealthReport = healthScheduler.runNow()

    // ─── Diagnostics ──────────────────────────────────────────────────────────

    suspend fun runDiagnostics(): DiagnosticReport = diagnosticsRunner.runFull()

    fun getLatestDiagnosticReport(): String? = diagnosticsRunner.getLatestReport()

    // ─── Performance ──────────────────────────────────────────────────────────

    fun getPerformanceReport(): PerformanceReport = performanceMonitor.buildReport()

    fun savePerformanceReport() = performanceMonitor.saveReport()

    // ─── Validation ───────────────────────────────────────────────────────────

    suspend fun runStartupValidation(): StartupValidationReport = startupValidator.validate()

    // ─── Crash ────────────────────────────────────────────────────────────────

    fun getRecentCrashes() = crashLogger.loadAll()

    fun clearCrashLogs() = crashLogger.clear()

    fun acknowledgeRecovery() = recoveryManager.clearRecovery()
}
