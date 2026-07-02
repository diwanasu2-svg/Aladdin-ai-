package com.aladdin.reliability.watchdog

/**
 * Exponential backoff: 5s → 10s → 30s → 1m → 5m (capped).
 */
object BackoffStrategy {
    private val STEPS_MS = longArrayOf(5_000, 10_000, 30_000, 60_000, 300_000)

    fun delayMs(attempt: Int): Long = STEPS_MS[minOf(attempt, STEPS_MS.size - 1)]

    fun reset() {}   // stateless — caller tracks attempt count
}
