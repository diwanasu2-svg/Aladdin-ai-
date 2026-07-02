package com.aladdin.reliability.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Captures logcat output to disk, rotates daily, keeps max 10 files, optionally compresses.
 */
class RotatingLogManager(
    private val context: Context,
    private val config: LogConfig = LogConfig()
) {
    companion object {
        private const val TAG = "RotatingLogManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logcatProcess: Process? = null
    private var currentFile: File? = null
    private var running = false

    private val logDir: File
        get() = File(context.filesDir, config.logDir).also { it.mkdirs() }

    fun start() {
        if (running) return
        running = true
        openNewLogFile()
        if (config.captureLogcat) startLogcatCapture()
        scheduleRotation()
        Log.i(TAG, "RotatingLogManager started. Dir: ${logDir.absolutePath}")
    }

    fun stop() {
        running = false
        logcatProcess?.destroy()
        logcatProcess = null
        scope.cancel()
        Log.i(TAG, "RotatingLogManager stopped")
    }

    /** Write a custom log line to the current log file */
    fun write(line: String) {
        currentFile?.let { f ->
            try {
                FileOutputStream(f, true).bufferedWriter().use { w ->
                    w.write("[${Date()}] $line\n")
                }
                rotateIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    fun getLogFiles(): List<File> =
        logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun deleteAll() = logDir.listFiles()?.forEach { it.delete() }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun openNewLogFile() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(logDir, "aladdin_$ts.log")
        Log.i(TAG, "Opened log file: ${currentFile?.name}")
    }

    private fun startLogcatCapture() {
        scope.launch {
            try {
                val buffers = config.logcatBuffers.flatMap { listOf("-b", it) }
                val cmd = listOf("logcat", "-v", "time") + buffers
                logcatProcess = ProcessBuilder(cmd).redirectErrorStream(true).start()
                logcatProcess!!.inputStream.bufferedReader().forEachLine { line ->
                    if (!running) return@forEachLine
                    currentFile?.let { f ->
                        FileOutputStream(f, true).bufferedWriter().use { it.write("$line\n") }
                    }
                    rotateIfNeeded()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logcat capture failed", e)
            }
        }
    }

    private fun scheduleRotation() {
        if (!config.rotateDaily) return
        scope.launch {
            var lastDate = currentDate()
            while (running) {
                delay(60_000)
                val today = currentDate()
                if (today != lastDate) {
                    lastDate = today
                    rotateNow()
                }
            }
        }
    }

    private fun rotateIfNeeded() {
        val f = currentFile ?: return
        if (f.length() >= config.maxFileSizeBytes) rotateNow()
    }

    private fun rotateNow() {
        val old = currentFile
        openNewLogFile()
        if (config.compress && old != null && old.exists()) {
            scope.launch { LogCompressor.compress(old) }
        }
        pruneOldFiles()
        Log.i(TAG, "Log rotated → ${currentFile?.name}")
    }

    private fun pruneOldFiles() {
        val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > config.maxFiles) files.drop(config.maxFiles).forEach { it.delete() }
    }

    private fun currentDate(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
}
