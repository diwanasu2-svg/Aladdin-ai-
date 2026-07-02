package com.aladdin.app.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PerformanceMonitor — Items 94-96: CPU, RAM, battery, latency tracking.
 *
 * Tracks app-level performance metrics and exposes them for diagnostics.
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val WARN_RAM_PERCENT = 85
    }

    data class PerformanceSnapshot(
        val timestampMs: Long    = System.currentTimeMillis(),
        val heapUsedMb: Float,
        val heapMaxMb: Float,
        val heapUsedPercent: Float,
        val nativeHeapMb: Float,
        val cpuPercent: Float,
        val availableRamMb: Long,
        val totalRamMb: Long,
        val isLowMemory: Boolean,
        val batteryLevel: Int = -1
    )

    data class LatencyRecord(val operationName: String, val latencyMs: Long, val timestampMs: Long = System.currentTimeMillis())

    private val latencyHistory = ArrayDeque<LatencyRecord>(200)
    private val latencyAverages = mutableMapOf<String, MutableList<Long>>()

    // ── Snapshot ──────────────────────────────────────────────────────────────

    suspend fun snapshot(): PerformanceSnapshot = withContext(Dispatchers.Default) {
        val runtime   = Runtime.getRuntime()
        val heapUsed  = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576f
        val heapMax   = runtime.maxMemory() / 1_048_576f
        val heapPct   = if (heapMax > 0) heapUsed / heapMax * 100f else 0f
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / 1_048_576f

        val am         = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo    = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availRam   = memInfo.availMem / 1_048_576L
        val totalRam   = memInfo.totalMem / 1_048_576L

        if (heapPct > WARN_RAM_PERCENT) Log.w(TAG, "High heap usage: ${"%.1f".format(heapPct)}%")
        if (memInfo.lowMemory)          Log.w(TAG, "System low memory flag set")

        PerformanceSnapshot(
            heapUsedMb      = heapUsed,
            heapMaxMb       = heapMax,
            heapUsedPercent = heapPct,
            nativeHeapMb    = nativeHeap,
            cpuPercent      = readCpuPercent(),
            availableRamMb  = availRam,
            totalRamMb      = totalRam,
            isLowMemory     = memInfo.lowMemory
        )
    }

    // ── Latency tracking ──────────────────────────────────────────────────────

    fun recordLatency(operationName: String, latencyMs: Long) {
        latencyHistory.addFirst(LatencyRecord(operationName, latencyMs))
        if (latencyHistory.size > 200) latencyHistory.removeLast()
        latencyAverages.getOrPut(operationName) { mutableListOf() }.add(latencyMs)
        Log.d(TAG, "Latency [$operationName]: ${latencyMs}ms")
    }

    inline fun <T> measureLatency(operationName: String, block: () -> T): T {
        val start  = System.currentTimeMillis()
        val result = block()
        recordLatency(operationName, System.currentTimeMillis() - start)
        return result
    }

    fun getAverageLatency(operationName: String): Double {
        val samples = latencyAverages[operationName] ?: return 0.0
        return if (samples.isEmpty()) 0.0 else samples.average()
    }

    fun getLatencyHistory(operationName: String? = null): List<LatencyRecord> =
        if (operationName == null) latencyHistory.toList()
        else latencyHistory.filter { it.operationName == operationName }

    // ── CPU ───────────────────────────────────────────────────────────────────

    private fun readCpuPercent(): Float {
        return try {
            val statFile = File("/proc/stat")
            if (!statFile.exists()) return -1f
            val lines = statFile.readLines()
            val cpu = lines.firstOrNull { it.startsWith("cpu ") } ?: return -1f
            val nums = cpu.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
            val idle  = nums.getOrElse(3) { 0L } + nums.getOrElse(4) { 0L }
            val total = nums.sum()
            if (total == 0L) return -1f
            (1f - idle.toFloat() / total.toFloat()) * 100f
        } catch (_: Exception) { -1f }
    }

    // ── Suggestions ───────────────────────────────────────────────────────────

    suspend fun getOptimizationSuggestions(): List<String> {
        val snap = snapshot()
        return buildList {
            if (snap.heapUsedPercent > 80)  add("Heap usage is ${snap.heapUsedPercent.toInt()}% — consider clearing caches")
            if (snap.isLowMemory)           add("System low memory — reduce background tasks")
            if (snap.availableRamMb < 200)  add("Less than 200 MB RAM available — close unused components")
            val slowOps = latencyAverages.entries.filter { (_, v) -> v.average() > 3000 }.map { it.key }
            if (slowOps.isNotEmpty())       add("Slow operations (>3s avg): ${slowOps.joinToString()}")
        }
    }
}
