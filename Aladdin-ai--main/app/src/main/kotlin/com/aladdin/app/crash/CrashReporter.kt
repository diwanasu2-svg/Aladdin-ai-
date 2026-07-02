package com.aladdin.app.crash

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CrashReporter — Item 99: Crash capture, storage, and graceful recovery.
 *
 * Installs as the global UncaughtExceptionHandler to capture crashes.
 * Stores them to disk for next-launch inspection.
 * Provides a crash summary for the health dashboard.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG       = "CrashReporter"
        private const val DB_FILE   = "crash_log.json"
        private const val MAX_LOGS  = 50
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    data class CrashEntry(
        val id: String,
        val timestamp: Long,
        val exceptionClass: String,
        val message: String,
        val stackTrace: String,
        val thread: String,
        val appVersion: String = ""
    )

    private val logFile  = File(context.filesDir, DB_FILE)
    private val crashes  = mutableListOf<CrashEntry>()
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    init { loadFromDisk() }

    // ── Install ───────────────────────────────────────────────────────────────

    fun install() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { capture(thread, throwable) } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "CrashReporter installed. Previous crash count: ${crashes.size}")
    }

    fun uninstall() {
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    fun capture(thread: Thread, throwable: Throwable) {
        val entry = CrashEntry(
            id             = "${System.currentTimeMillis()}",
            timestamp      = System.currentTimeMillis(),
            exceptionClass = throwable.javaClass.name,
            message        = throwable.message ?: "(no message)",
            stackTrace     = throwable.stackTraceToString().take(4096),
            thread         = thread.name,
            appVersion     = getAppVersion()
        )
        crashes.add(0, entry)
        if (crashes.size > MAX_LOGS) crashes.subList(MAX_LOGS, crashes.size).clear()
        saveToDisk()
        Log.e(TAG, "CRASH CAPTURED: [${entry.exceptionClass}] ${entry.message} on thread=${entry.thread}")
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    fun getRecentCrashes(limit: Int = 10): List<CrashEntry> = crashes.take(limit)
    fun getCrashCount(): Int = crashes.size
    fun hasPreviousCrash(): Boolean = crashes.isNotEmpty()

    fun getLastCrashSummary(): String? {
        val c = crashes.firstOrNull() ?: return null
        return "[${DATE_FMT.format(Date(c.timestamp))}] ${c.exceptionClass}: ${c.message.take(120)}"
    }

    fun clearCrashLog() { crashes.clear(); saveToDisk() }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveToDisk() {
        try {
            val arr = JSONArray()
            crashes.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id); put("timestamp", c.timestamp)
                    put("exceptionClass", c.exceptionClass); put("message", c.message)
                    put("stackTrace", c.stackTrace); put("thread", c.thread)
                    put("appVersion", c.appVersion)
                })
            }
            logFile.writeText(arr.toString())
        } catch (e: Exception) { Log.e(TAG, "Save error: ${e.message}") }
    }

    private fun loadFromDisk() {
        if (!logFile.exists()) return
        try {
            val arr = JSONArray(logFile.readText())
            crashes.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                crashes.add(CrashEntry(
                    id             = o.optString("id"),
                    timestamp      = o.optLong("timestamp"),
                    exceptionClass = o.optString("exceptionClass"),
                    message        = o.optString("message"),
                    stackTrace     = o.optString("stackTrace"),
                    thread         = o.optString("thread"),
                    appVersion     = o.optString("appVersion")
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Load error: ${e.message}") }
    }

    private fun getAppVersion(): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.versionCode})"
    } catch (_: Exception) { "unknown" }
}
