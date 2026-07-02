package com.aladdin.reliability.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Persists crash reports to disk so they survive process death.
 * Stored in app's files dir: crashes/crash_<timestamp>.txt
 */
class CrashLogger(private val context: Context) {

    companion object {
        private const val TAG = "CrashLogger"
        private const val CRASH_DIR = "crashes"
        private const val MAX_CRASH_FILES = 20
    }

    private val crashDir: File
        get() = File(context.filesDir, CRASH_DIR).also { it.mkdirs() }

    fun save(report: CrashReport) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.timestampMs))
            val file = File(crashDir, "crash_${ts}_${report.id.take(8)}.txt")
            file.writeText(report.toLogString())
            Log.i(TAG, "Crash report saved: ${file.name}")
            pruneOldFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report", e)
        }
    }

    fun loadAll(): List<CrashReport> {
        return crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseFile(it) }
            ?: emptyList()
    }

    fun getLatest(): CrashReport? = loadAll().firstOrNull()

    fun clear() = crashDir.listFiles()?.forEach { it.delete() }

    private fun pruneOldFiles() {
        val files = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CRASH_FILES) files.drop(MAX_CRASH_FILES).forEach { it.delete() }
    }

    private fun parseFile(file: File): CrashReport? = runCatching {
        val lines = file.readLines()
        val thread = lines.find { it.startsWith("Thread") }?.substringAfter(": ") ?: "unknown"
        val exc    = lines.find { it.startsWith("Exception") }?.substringAfter(": ") ?: "Unknown"
        val msg    = lines.find { it.startsWith("Message") }?.substringAfter(": ")
        val stack  = lines.dropWhile { !it.startsWith("--- Stack") }.drop(1)
                         .takeWhile { !it.startsWith("===") }.joinToString("\n")
        CrashReport(threadName = thread, exceptionClass = exc, message = msg,
                    stackTrace = stack, appVersion = "unknown")
    }.getOrNull()
}
