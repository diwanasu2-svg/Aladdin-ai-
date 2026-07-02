package com.aladdin.performance.startup

import android.util.Log

data class StartupMilestone(val name: String, val elapsedMs: Long)

/**
 * Tracks startup milestones from app launch to "ready" state.
 * Target: total startup time < 3000 ms.
 */
object StartupTracker {
    private const val TAG = "StartupTracker"
    const val TARGET_MS = 3_000L

    private var originMs = 0L
    private val milestones = mutableListOf<StartupMilestone>()

    fun begin() { originMs = System.currentTimeMillis(); milestones.clear() }

    fun mark(name: String): Long {
        val elapsed = System.currentTimeMillis() - originMs
        milestones.add(StartupMilestone(name, elapsed))
        Log.d(TAG, "[${"% 6d".format(elapsed)}ms] $name")
        return elapsed
    }

    fun finish(name: String = "READY"): Long {
        val total = mark(name)
        if (total > TARGET_MS) Log.w(TAG, "Startup SLOW: ${total}ms > target ${TARGET_MS}ms")
        else                   Log.i(TAG, "Startup OK: ${total}ms (target ${TARGET_MS}ms)")
        return total
    }

    fun report(): String = buildString {
        appendLine("=== Startup Timeline ===")
        milestones.forEach { appendLine("  ${"% 6d".format(it.elapsedMs)}ms  ${it.name}") }
        appendLine("Target: ${TARGET_MS}ms | Actual: ${milestones.lastOrNull()?.elapsedMs ?: 0}ms")
    }

    fun totalMs() = milestones.lastOrNull()?.elapsedMs ?: 0L
    fun metTarget() = totalMs() <= TARGET_MS
    fun getMilestones(): List<StartupMilestone> = milestones.toList()
}
