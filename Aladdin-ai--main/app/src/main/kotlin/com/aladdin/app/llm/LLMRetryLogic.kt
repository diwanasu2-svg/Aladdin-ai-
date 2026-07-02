package com.aladdin.app.llm

import android.util.Log
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * LLMRetryLogic — Item 93: Exponential backoff with jitter for LLM API failures.
 *
 * Wraps any suspending LLM call with automatic retry logic:
 * - Exponential backoff (base × 2^attempt) with ±20 % jitter
 * - Configurable max attempts and delay caps
 * - Retries on transient errors (network, rate-limit, server 5xx)
 * - Immediately fails on permanent errors (bad key, 400 bad request)
 */
@Singleton
class LLMRetryLogic @Inject constructor() {
    companion object {
        private const val TAG = "LLMRetryLogic"
    }

    data class RetryConfig(
        val maxAttempts: Int     = 3,
        val baseDelayMs: Long   = 1_000L,
        val maxDelayMs: Long    = 30_000L,
        val jitterFraction: Double = 0.2
    )

    class PermanentFailureException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ── Retry wrapper ─────────────────────────────────────────────────────────

    suspend fun <T> withRetry(
        config: RetryConfig = RetryConfig(),
        operationName: String = "LLM call",
        isPermanentFailure: (Throwable) -> Boolean = ::defaultPermanentFailureCheck,
        block: suspend (attempt: Int) -> T
    ): T {
        var lastException: Throwable? = null
        for (attempt in 0 until config.maxAttempts) {
            try {
                val result = block(attempt)
                if (attempt > 0) Log.i(TAG, "$operationName succeeded on attempt ${attempt + 1}")
                return result
            } catch (e: PermanentFailureException) {
                Log.e(TAG, "$operationName permanent failure: ${e.message}")
                throw e
            } catch (e: Throwable) {
                lastException = e
                if (isPermanentFailure(e)) {
                    Log.e(TAG, "$operationName permanent error (no retry): ${e.message}")
                    throw PermanentFailureException("$operationName failed: ${e.message}", e)
                }
                if (attempt < config.maxAttempts - 1) {
                    val delay = backoffDelay(attempt, config)
                    Log.w(TAG, "$operationName attempt ${attempt + 1}/${config.maxAttempts} failed: ${e.message}. Retrying in ${delay}ms")
                    delay(delay)
                } else {
                    Log.e(TAG, "$operationName exhausted ${config.maxAttempts} attempts: ${e.message}")
                }
            }
        }
        throw lastException ?: RuntimeException("$operationName failed after ${config.maxAttempts} attempts")
    }

    // ── Backoff calculation ───────────────────────────────────────────────────

    private fun backoffDelay(attempt: Int, config: RetryConfig): Long {
        val base    = config.baseDelayMs * 2.0.pow(attempt.toDouble())
        val capped  = min(base, config.maxDelayMs.toDouble())
        val jitter  = capped * config.jitterFraction * (Math.random() * 2 - 1) // ±jitterFraction
        return (capped + jitter).toLong().coerceAtLeast(100L)
    }

    // ── Permanent-failure heuristics ──────────────────────────────────────────

    private fun defaultPermanentFailureCheck(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("401") || msg.contains("403") ||
               msg.contains("invalid api key") || msg.contains("bad request") ||
               msg.contains("400") || msg.contains("invalid_api_key") ||
               msg.contains("permission denied") || e is PermanentFailureException
    }
}
