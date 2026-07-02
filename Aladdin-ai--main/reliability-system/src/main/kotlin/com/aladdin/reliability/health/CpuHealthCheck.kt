package com.aladdin.reliability.health

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * Reads CPU usage from /proc/stat.
 * Returns a 0–100 float representing total CPU busy percentage since last call.
 */
class CpuHealthCheck {

    companion object {
        private const val TAG = "CpuHealthCheck"
        const val CPU_WARN_THRESHOLD = 85f
    }

    private var lastIdle: Long = 0L
    private var lastTotal: Long = 0L

    data class Result(val cpuPercent: Float, val healthy: Boolean, val issue: String?)

    fun check(): Result {
        val (idle, total) = readProcStat()
        val diffIdle  = idle  - lastIdle
        val diffTotal = total - lastTotal
        lastIdle  = idle
        lastTotal = total

        val percent = if (diffTotal > 0) (1f - diffIdle.toFloat() / diffTotal) * 100f else 0f
        val healthy = percent < CPU_WARN_THRESHOLD
        return Result(
            cpuPercent = percent,
            healthy    = healthy,
            issue      = if (!healthy) "CPU at ${"%.1f".format(percent)}% (threshold ${CPU_WARN_THRESHOLD}%)" else null
        )
    }

    private fun readProcStat(): Pair<Long, Long> {
        return try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val line = reader.readLine() ?: return Pair(0L, 0L)
                val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
                val idle  = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }
                val total = parts.sum()
                Pair(idle, total)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read /proc/stat: ${e.message}")
            Pair(lastIdle, lastTotal)
        }
    }
}
