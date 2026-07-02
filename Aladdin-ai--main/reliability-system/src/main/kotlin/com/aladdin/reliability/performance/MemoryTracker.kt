package com.aladdin.reliability.performance

import android.app.ActivityManager
import android.content.Context

class MemoryTracker(private val context: Context) {
    data class Sample(val usedMb: Long, val totalMb: Long, val percentUsed: Float)

    fun sample(): Sample {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val usedMb  = totalMb - mi.availMem / (1024 * 1024)
        val percent = if (totalMb > 0) usedMb * 100f / totalMb else 0f
        return Sample(usedMb, totalMb, percent)
    }
}
