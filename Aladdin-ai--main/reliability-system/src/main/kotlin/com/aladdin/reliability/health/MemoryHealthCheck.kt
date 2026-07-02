package com.aladdin.reliability.health

import android.app.ActivityManager
import android.content.Context

class MemoryHealthCheck(private val context: Context) {

    companion object {
        const val MEMORY_WARN_PERCENT = 90f
    }

    data class Result(val usedMb: Long, val totalMb: Long, val healthy: Boolean, val issue: String?)

    fun check(): Result {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val availMb = mi.availMem / (1024 * 1024)
        val usedMb  = totalMb - availMb
        val percent = if (totalMb > 0) usedMb * 100f / totalMb else 0f
        val healthy = percent < MEMORY_WARN_PERCENT && !mi.lowMemory
        val issue   = when {
            mi.lowMemory -> "Low memory condition reported by OS"
            percent >= MEMORY_WARN_PERCENT -> "Memory at ${"%.1f".format(percent)}% (threshold ${MEMORY_WARN_PERCENT}%)"
            else -> null
        }
        return Result(usedMb = usedMb, totalMb = totalMb, healthy = healthy, issue = issue)
    }
}
