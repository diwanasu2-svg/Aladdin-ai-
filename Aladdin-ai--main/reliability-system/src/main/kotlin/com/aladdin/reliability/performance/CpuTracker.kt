package com.aladdin.reliability.performance

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

class CpuTracker {
    private var lastIdle = 0L; private var lastTotal = 0L
    data class Sample(val percent: Float)

    fun sample(): Sample {
        return try {
            BufferedReader(FileReader("/proc/stat")).use { r ->
                val parts = r.readLine().split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
                val idle  = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }
                val total = parts.sum()
                val di = idle - lastIdle; val dt = total - lastTotal
                lastIdle = idle; lastTotal = total
                Sample(if (dt > 0) (1f - di.toFloat() / dt) * 100f else 0f)
            }
        } catch (e: Exception) { Sample(0f) }
    }
}
