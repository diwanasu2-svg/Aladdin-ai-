package com.aladdin.reliability.crash

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Global UncaughtExceptionHandler.
 * Captures crash details, saves the report, and schedules an app restart.
 */
class CrashHandler(
    private val context: Context,
    private val logger: CrashLogger,
    private val appVersion: String,
    private val restartIntent: Intent? = null
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        const val PREF_CRASH_RECOVERY = "aladdin_crash_recovery"
        const val KEY_RECOVERY_ATTEMPTS = "recovery_attempts"
        const val KEY_LAST_CRASH_MS = "last_crash_ms"
        const val MAX_RAPID_CRASHES = 5
        const val RAPID_CRASH_WINDOW_MS = 60_000L

        fun install(context: Context, logger: CrashLogger, appVersion: String, restartIntent: Intent? = null) {
            val handler = CrashHandler(context, logger, appVersion, restartIntent)
            handler.defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.i(TAG, "CrashHandler installed")
        }
    }

    var defaultHandler: Thread.UncaughtExceptionHandler? = null

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val prefs = context.getSharedPreferences(PREF_CRASH_RECOVERY, Context.MODE_PRIVATE)
            val attempts = prefs.getInt(KEY_RECOVERY_ATTEMPTS, 0)
            val lastCrashMs = prefs.getLong(KEY_LAST_CRASH_MS, 0L)
            val now = System.currentTimeMillis()

            // Check for crash loop
            val isRapidCrash = (now - lastCrashMs) < RAPID_CRASH_WINDOW_MS
            val newAttempts = if (isRapidCrash) attempts + 1 else 1

            prefs.edit()
                .putInt(KEY_RECOVERY_ATTEMPTS, newAttempts)
                .putLong(KEY_LAST_CRASH_MS, now)
                .apply()

            val report = CrashReport(
                threadName    = thread.name,
                exceptionClass = throwable.javaClass.name,
                message       = throwable.message,
                stackTrace    = throwable.stackTraceToString(),
                appVersion    = appVersion,
                availableMemoryMb = getAvailableMemoryMb(),
                recoveryAttempts  = newAttempts
            )
            logger.save(report)
            Log.e(TAG, "Uncaught exception captured (attempt #$newAttempts)", throwable)

            // Attempt restart if not in crash loop
            if (newAttempts <= MAX_RAPID_CRASHES && restartIntent != null) {
                scheduleRestart(restartIntent, newAttempts)
            } else {
                Log.w(TAG, "Crash loop detected ($newAttempts crashes) — skipping restart")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CrashHandler itself threw", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun scheduleRestart(intent: Intent, attempt: Int) {
        val delayMs = when (attempt) {
            1 -> 500L; 2 -> 1_000L; 3 -> 3_000L; 4 -> 5_000L
            else -> 10_000L
        }
        Log.i(TAG, "Scheduling restart in ${delayMs}ms")
        Thread {
            Thread.sleep(delayMs)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }.also { it.isDaemon = true }.start()
    }

    private fun getAvailableMemoryMb(): Long {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }
}
