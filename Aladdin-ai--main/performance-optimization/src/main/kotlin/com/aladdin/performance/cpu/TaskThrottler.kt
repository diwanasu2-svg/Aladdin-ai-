package com.aladdin.performance.cpu

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Rate-limits a recurring background task to enforce an inter-execution gap.
 * When idle: coalesces pending work and extends the gap to [idleIntervalMs].
 *
 * Example: throttle AI polling to max once per 500 ms when active,
 *          once per 5 s when the user has been idle for > 30 s.
 */
class TaskThrottler(
    private val name: String,
    private val activeIntervalMs: Long  = 500L,
    private val idleIntervalMs: Long    = 5_000L,
    private val idleThresholdMs: Long   = 30_000L
) {
    companion object { private const val TAG = "TaskThrottler" }

    private val lastRunMs    = AtomicLong(0L)
    private val lastActiveMs = AtomicLong(System.currentTimeMillis())
    private val pending      = AtomicBoolean(false)

    fun onUserActivity() { lastActiveMs.set(System.currentTimeMillis()) }

    fun isIdle() = System.currentTimeMillis() - lastActiveMs.get() > idleThresholdMs

    fun currentIntervalMs() = if (isIdle()) idleIntervalMs else activeIntervalMs

    /** Returns true if the task should run now; false if it should be skipped/deferred. */
    fun shouldRun(): Boolean {
        val now = System.currentTimeMillis()
        val gap = now - lastRunMs.get()
        return if (gap >= currentIntervalMs()) {
            lastRunMs.set(now); pending.set(false); true
        } else {
            pending.set(true)
            Log.v(TAG, "$name throttled (gap=${gap}ms < ${currentIntervalMs()}ms)")
            false
        }
    }

    /** Suspend until the task is allowed to run */
    suspend fun waitForSlot() {
        val gap = currentIntervalMs() - (System.currentTimeMillis() - lastRunMs.get())
        if (gap > 0) { Log.v(TAG, "$name waiting ${gap}ms"); delay(gap) }
        lastRunMs.set(System.currentTimeMillis())
    }

    fun hasPending() = pending.get()
    fun reset() { lastRunMs.set(0L) }
}
