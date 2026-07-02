package com.aladdin.reliability.performance

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PerformanceMonitor(
    private val context: Context,
    private val sampleIntervalMs: Long = 30_000L
) {
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val REPORTS_DIR = "perf_reports"
        private const val MAX_REPORTS = 10
        private const val MAX_SAMPLES_IN_MEMORY = 120
    }

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cpu     = CpuTracker()
    private val mem     = MemoryTracker(context)
    private val battery = BatteryTracker(context)

    private val samples = ArrayDeque<PerformanceSample>()
    private var running = false
    private var startMs = 0L

    fun start() {
        if (running) return
        running = true; startMs = System.currentTimeMillis()
        scope.launch {
            Log.i(TAG, "PerformanceMonitor started (interval ${sampleIntervalMs / 1000}s)")
            while (running) {
                takeSample()
                delay(sampleIntervalMs)
            }
        }
    }

    fun stop(): PerformanceReport {
        running = false
        scope.cancel()
        return buildReport()
    }

    fun getLatestSample(): PerformanceSample? = samples.lastOrNull()

    fun buildReport(): PerformanceReport {
        val duration = System.currentTimeMillis() - startMs
        return PerformanceReport(samples = samples.toList(), durationMs = duration)
    }

    fun saveReport(): File? {
        return try {
            val report = buildReport()
            val dir = File(context.filesDir, REPORTS_DIR).also { it.mkdirs() }
            val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f   = File(dir, "perf_$ts.md")
            f.writeText(report.toMarkdown())
            pruneReports(dir)
            Log.i(TAG, "Performance report saved: ${f.name}")
            f
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save performance report", e)
            null
        }
    }

    private fun takeSample() {
        val c = cpu.sample(); val m = mem.sample(); val b = battery.sample()
        val s = PerformanceSample(
            cpuPercent = c.percent, memoryUsedMb = m.usedMb, memoryTotalMb = m.totalMb,
            batteryPercent = b.percent, batteryCharging = b.charging, batteryTemperatureC = b.temperatureC
        )
        samples.addLast(s)
        if (samples.size > MAX_SAMPLES_IN_MEMORY) samples.removeFirst()
    }

    private fun pruneReports(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }
}
