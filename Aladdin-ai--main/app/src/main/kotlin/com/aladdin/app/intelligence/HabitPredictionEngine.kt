package com.aladdin.app.intelligence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp

// ─── Phase 11 Item 2: Habit Prediction Engine — Bayesian + time-pattern model ─

@Singleton
class HabitPredictionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG  = "HabitPrediction"
        private const val PREF = "habit_data"
        private const val MAX_EVENTS = 500
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class HabitEvent(
        val action: String,
        val hourOfDay: Int,
        val dayOfWeek: Int,
        val timestampMs: Long
    )

    data class HabitPrediction(
        val action: String,
        val probability: Double,
        val expectedHour: Int,
        val confidence: String,
        val reason: String
    )

    // ── Record an observed action ─────────────────────────────────────────────
    suspend fun recordAction(action: String) = withContext(Dispatchers.IO) {
        try {
            val cal = java.util.Calendar.getInstance()
            val event = HabitEvent(
                action     = action,
                hourOfDay  = cal.get(java.util.Calendar.HOUR_OF_DAY),
                dayOfWeek  = cal.get(java.util.Calendar.DAY_OF_WEEK),
                timestampMs = System.currentTimeMillis()
            )
            val all = loadEvents().toMutableList()
            all.add(0, event)
            if (all.size > MAX_EVENTS) all.subList(MAX_EVENTS, all.size).clear()
            saveEvents(all)
            Log.d(TAG, "Recorded action: $action at ${event.hourOfDay}:00 day=${event.dayOfWeek}")
        } catch (e: Exception) {
            Log.e(TAG, "recordAction failed: ${e.message}")
        }
    }

    // ── Predict what user might want next ─────────────────────────────────────
    suspend fun predict(topN: Int = 3): List<HabitPrediction> = withContext(Dispatchers.IO) {
        try {
            val all   = loadEvents()
            val cal   = java.util.Calendar.getInstance()
            val hour  = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val dow   = cal.get(java.util.Calendar.DAY_OF_WEEK)

            if (all.isEmpty()) return@withContext emptyList()

            // Group by action
            val actionGroups = all.groupBy { it.action }

            actionGroups.map { (action, events) ->
                // Bayesian: P(action | time) ∝ P(action) * P(time | action)
                val prior      = events.size.toDouble() / all.size
                val timeProb   = events.count { abs(it.hourOfDay - hour) <= 1 && it.dayOfWeek == dow }
                    .toDouble() / events.size.coerceAtLeast(1)
                val posterior  = prior * (timeProb + 0.05)  // +0.05 Laplace smoothing

                val avgHour = events.map { it.hourOfDay }.average().toInt()
                val conf    = when {
                    events.size >= 20 && timeProb > 0.5 -> "High"
                    events.size >= 10 && timeProb > 0.2 -> "Medium"
                    else                                 -> "Low"
                }
                val reason = buildString {
                    append("Observed ${events.size}x")
                    if (timeProb > 0.2) append(", often around ${avgHour}:00")
                    if (events.any { it.dayOfWeek == dow }) append(" on this day of week")
                }

                HabitPrediction(
                    action      = action,
                    probability = posterior,
                    expectedHour = avgHour,
                    confidence  = conf,
                    reason      = reason
                )
            }.sortedByDescending { it.probability }.take(topN)

        } catch (e: Exception) {
            Log.e(TAG, "predict failed: ${e.message}")
            emptyList()
        }
    }

    // ── Detect recurring patterns ─────────────────────────────────────────────
    suspend fun detectPatterns(): List<String> = withContext(Dispatchers.IO) {
        try {
            val all = loadEvents()
            val patterns = mutableListOf<String>()

            // Morning routine detection
            val morningActions = all.filter { it.hourOfDay in 6..9 }
                .groupBy { it.action }
                .filter { it.value.size >= 5 }
            if (morningActions.isNotEmpty()) {
                patterns.add("Morning routine: ${morningActions.keys.take(3).joinToString(", ")}")
            }

            // Evening routine detection
            val eveningActions = all.filter { it.hourOfDay in 18..22 }
                .groupBy { it.action }
                .filter { it.value.size >= 5 }
            if (eveningActions.isNotEmpty()) {
                patterns.add("Evening routine: ${eveningActions.keys.take(3).joinToString(", ")}")
            }

            // Weekly pattern
            val weekdayActions = all.filter { it.dayOfWeek in 2..6 }
                .groupBy { it.action }
                .maxByOrNull { it.value.size }
            weekdayActions?.let {
                if (it.value.size >= 3) patterns.add("Weekday habit: ${it.key}")
            }

            patterns
        } catch (e: Exception) {
            Log.e(TAG, "detectPatterns failed: ${e.message}")
            emptyList()
        }
    }

    private fun loadEvents(): List<HabitEvent> {
        return try {
            val json = prefs.getString("events", null) ?: return emptyList()
            val arr  = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                HabitEvent(
                    action      = obj.getString("action"),
                    hourOfDay   = obj.getInt("hour"),
                    dayOfWeek   = obj.getInt("dow"),
                    timestampMs = obj.getLong("ts")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveEvents(events: List<HabitEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("action", e.action)
                put("hour",   e.hourOfDay)
                put("dow",    e.dayOfWeek)
                put("ts",     e.timestampMs)
            })
        }
        prefs.edit().putString("events", arr.toString()).apply()
    }
}
