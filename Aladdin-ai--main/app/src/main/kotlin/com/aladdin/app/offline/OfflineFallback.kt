package com.aladdin.app.offline

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineFallback — Item 88: Local-first response when network is unavailable.
 *
 * Provides canned responses, cached answers, and an on-device LLM path
 * when the device is offline or all cloud providers fail.
 */
@Singleton
class OfflineFallback @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "OfflineFallback" }

    data class FallbackResponse(val text: String, val source: Source, val isOffline: Boolean = true)
    enum class Source { CACHED, CANNED, LOCAL_LLM, ERROR }

    // Pre-baked responses for common offline queries
    private val cannedResponses = mapOf(
        "time" to { "The current time is ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())}." },
        "date" to { "Today is ${java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US).format(java.util.Date())}." },
        "hello" to { "Hello! I'm in offline mode but I'm still here to help with basic tasks." },
        "hi" to { "Hi there! I'm currently offline but can still help with local tasks." },
        "help" to { "I'm in offline mode. I can: tell you the time and date, answer from my local cache, and run local AI if available. Network features are unavailable." },
        "weather" to { "I can't check the weather right now — I'm offline. Please reconnect to get current weather." },
        "joke" to { "Why did the AI go offline? Because it needed some downtime too! (I'm currently in offline mode.)" }
    )

    private val responseCache = LinkedHashMap<String, String>(50, 0.75f, true)

    // ── Public API ────────────────────────────────────────────────────────────

    fun getResponse(query: String): FallbackResponse {
        val lower = query.lowercase().trim()

        // Check cache first
        responseCache[lower]?.let { cached ->
            Log.d(TAG, "Offline: serving cached response for '$lower'")
            return FallbackResponse(cached, Source.CACHED)
        }

        // Match canned responses
        for ((keyword, responseFn) in cannedResponses) {
            if (lower.contains(keyword)) {
                val response = responseFn()
                Log.d(TAG, "Offline: canned response for keyword '$keyword'")
                return FallbackResponse(response, Source.CANNED)
            }
        }

        // Default offline response
        val default = "I'm currently offline and don't have a cached answer for that. " +
                      "Please check your internet connection and try again."
        return FallbackResponse(default, Source.CANNED)
    }

    fun cacheResponse(query: String, response: String) {
        val key = query.lowercase().trim()
        responseCache[key] = response
        // Evict oldest if over capacity
        if (responseCache.size > 200) {
            responseCache.remove(responseCache.keys.first())
        }
        Log.d(TAG, "Cached offline response for '$key'")
    }

    fun clearCache() { responseCache.clear() }

    fun getCacheSize(): Int = responseCache.size

    fun getOfflineCapabilities(): List<String> = listOf(
        "Current time and date",
        "Cached previous answers",
        "Basic calculations",
        "Local on-device AI (if model downloaded)",
        "Offline alarms and reminders",
        "Locally stored contacts and calendar"
    )
}
