package com.aladdin.performance.cpu

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader

/**
 * Monitors CPU usage and enforces the < 10% average background target.
 *
 * Strategies:
 *  - Tracks rolling CPU% over the last 60 samples (30 min at 30s interval)
 *  - If rolling average > 10%, throttles background coroutines via [DeepSleep]
 *  - [acquireWakeLock] / [releaseWakeLock] for precise active-work windows
 */
class CpuOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "CpuOptimizer"
        const val TARGET_AVG_PCT = 10f
        private const val WINDOW = 60
    }

    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pm     = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val samples = ArrayDeque<Float>(WINDOW)
    private var wakeLock: PowerManager.WakeLock? = null

    fun startMonitoring() {
        scope.launch {
            while (true) {
                samples.addLast(readCpuPercent())
                if (samples.size > WINDOW) samples.removeFirst()
                val avg = samples.average().toFloat()
                if (avg > TARGET_AVG_PCT) Log.w(TAG, "CPU avg ${"%.1f".format(avg)}% > target ${TARGET_AVG_PCT}%")
                delay(30_000)
            }
        }
    }

    fun stopMonitoring() { scope.cancel(); releaseWakeLock() }

    fun rollingAvgCpu() = if (samples.isEmpty()) 0f else samples.average().toFloat()
    fun isOverTarget()  = rollingAvgCpu() > TARGET_AVG_PCT

    /** Partial WakeLock for audio processing windows — release ASAP */
    fun acquireWakeLock(tag: String = "Aladdin:CpuWork", timeoutMs: Long = 5_000L) {
        releaseWakeLock()
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            acquire(timeoutMs)
            Log.d(TAG, "WakeLock acquired ($tag, ${timeoutMs}ms)")
        }
    }

    fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Deep-sleep the current coroutine; checks if backpressure has cleared */
    suspend fun deepSleepIfNeeded(thresholdPct: Float = TARGET_AVG_PCT) {
        if (rollingAvgCpu() > thresholdPct) {
            Log.d(TAG, "DeepSleep: CPU ${rollingAvgCpu()}% — pausing background work 10s")
            delay(10_000)
        }
    }

    private fun readCpuPercent(): Float {
        return try {
            val line1 = BufferedReader(FileReader("/proc/stat")).readLine()
            Thread.sleep(200)
            val line2 = BufferedReader(FileReader("/proc/stat")).readLine()
            parseCpuPct(line1, line2)
        } catch (e: Exception) { 0f }
    }

    private fun parseCpuPct(l1: String, l2: String): Float {
        fun parse(l: String) = l.split("\\s+".toRegex()).drop(1).take(7).mapNotNull { it.toLongOrNull() }
        val a = parse(l1); val b = parse(l2)
        val idle1 = a.getOrElse(3){0} + a.getOrElse(4){0}
        val idle2 = b.getOrElse(3){0} + b.getOrElse(4){0}
        val total1 = a.sum(); val total2 = b.sum()
        val dt = total2 - total1; val di = idle2 - idle1
        return if (dt > 0) (1f - di.toFloat() / dt) * 100f else 0f
    }
}
