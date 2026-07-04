package com.aladdin.app.intelligence

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * IntelligenceEngine — Items 68-72.
 * Item 68: Screen automation — pattern-based trigger system.
 * Item 69: Emotion intelligence — multi-signal sentiment analysis.
 * Item 70: Recommendation engine — context-aware suggestions.
 * Item 71: Context awareness — time/battery/network awareness.
 * Item 72: Habit prediction — Bayesian model from interaction history.
 */
@Singleton
class IntelligenceEngine @Inject constructor(@ApplicationContext private val context: Context) {
    companion object { private const val TAG = "IntelligenceEngine"; private const val MAX_HIST = 1000 }

    // ── Item 69: Emotion ─────────────────────────────────────────────────────
    enum class Emotion { HAPPY, SAD, ANGRY, ANXIOUS, NEUTRAL, EXCITED, CONFUSED, FRUSTRATED }
    data class EmotionState(val primary: Emotion, val confidence: Float, val valence: Float, val arousal: Float)

    private val emotionKw = mapOf(
        Emotion.HAPPY to listOf("happy","great","wonderful","amazing","love","fantastic","joy","celebrate"),
        Emotion.SAD to listOf("sad","unhappy","depressed","miss","alone","terrible","awful","grief"),
        Emotion.ANGRY to listOf("angry","furious","annoyed","hate","frustrated","mad","rage"),
        Emotion.ANXIOUS to listOf("worried","nervous","anxious","scared","fear","panic","stress"),
        Emotion.EXCITED to listOf("excited","thrilled","awesome","incredible","yes!","yay","can't wait"),
        Emotion.CONFUSED to listOf("confused","don't understand","what does","unclear","lost"),
        Emotion.FRUSTRATED to listOf("frustrated","can't","doesn't work","broken","ugh","stupid","useless")
    )

    fun detectEmotion(text: String): EmotionState {
        val lower = text.lowercase()
        val scores = mutableMapOf<Emotion, Float>()
        emotionKw.forEach { (e, kws) -> val s = kws.count { lower.contains(it) }.toFloat(); if (s > 0) scores[e] = s / kws.size }
        if (text.count { it == '!' } >= 2) scores[Emotion.EXCITED] = (scores[Emotion.EXCITED] ?: 0f) + 0.2f
        if (text.count { it == '?' } > 2) scores[Emotion.CONFUSED] = (scores[Emotion.CONFUSED] ?: 0f) + 0.15f
        val primary = scores.maxByOrNull { it.value }?.key ?: Emotion.NEUTRAL
        val valence = when (primary) { Emotion.HAPPY, Emotion.EXCITED -> 0.8f; Emotion.SAD, Emotion.ANGRY, Emotion.FRUSTRATED -> -0.7f; Emotion.ANXIOUS -> -0.4f; else -> 0f }
        val arousal = when (primary) { Emotion.EXCITED, Emotion.ANGRY -> 0.9f; Emotion.HAPPY -> 0.6f; Emotion.SAD -> 0.2f; else -> 0.4f }
        return EmotionState(primary, (scores[primary] ?: 0f).coerceIn(0f, 1f), valence, arousal)
    }

    // ── Item 71: Context awareness ───────────────────────────────────────────
    data class DeviceContext(val hourOfDay: Int, val batteryPct: Int, val isCharging: Boolean, val networkType: String, val memMb: Long)

    fun buildContext(): DeviceContext {
        val cal = java.util.Calendar.getInstance()
        val bi = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"; caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"; else -> "none" }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return DeviceContext(cal.get(java.util.Calendar.HOUR_OF_DAY), lvl * 100 / scale, charging, net, mi.availMem / 1_048_576L)
    }

    // ── Item 70: Recommendations ─────────────────────────────────────────────
    data class Recommendation(val action: String, val reason: String, val score: Float)
    private val history = mutableListOf<Pair<String, Long>>()

    fun generateRecommendations(): List<Recommendation> {
        val ctx = buildContext(); val recs = mutableListOf<Recommendation>()
        when (ctx.hourOfDay) {
            in 6..9 -> recs += Recommendation("morning_brief", "Start your day with a briefing!", 0.9f)
            in 12..13 -> recs += Recommendation("lunch_check", "Lunch time. Need anything?", 0.7f)
            in 20..22 -> recs += Recommendation("day_summary", "End of day summary available", 0.8f)
        }
        if (ctx.batteryPct < 20) recs += Recommendation("battery_save", "Battery low (\${ctx.batteryPct}%) — enable power saving?", 0.95f)
        history.groupBy { it.first }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.take(2).forEach { (q, c) ->
            if (c >= 3) recs += Recommendation("repeat_\${q.take(15)}", "You often ask: \$q", c * 0.1f)
        }
        return recs.sortedByDescending { it.score }
    }

    fun recordInteraction(query: String) { history.add(Pair(query.take(100), System.currentTimeMillis())); if (history.size > MAX_HIST) history.removeAt(0); learnHabit(query) }

    // ── Item 72: Habit prediction ────────────────────────────────────────────
    data class HabitPrediction(val query: String, val probability: Float, val confidence: Float)
    private data class HabitPattern(val query: String, val hourCounts: MutableMap<Int, Int> = mutableMapOf(), var total: Int = 0)
    private val habits = mutableMapOf<String, HabitPattern>()

    fun learnHabit(query: String) {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val p = habits.getOrPut(query) { HabitPattern(query) }
        p.hourCounts[h] = (p.hourCounts[h] ?: 0) + 1; p.total++
    }

    fun predictHabits(topN: Int = 3): List<HabitPrediction> {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val total = habits.values.sumOf { it.total }.coerceAtLeast(1)
        return habits.values.filter { it.total >= 3 }.map { p ->
            val prior = p.total.toFloat() / total
            val like = ((p.hourCounts[h] ?: 0) + 1f) / (p.total + 24f)
            HabitPrediction(p.query, prior * like, if (p.total >= 10) 0.8f else 0.5f)
        }.sortedByDescending { it.probability }.take(topN)
    }

    // ── Item 68: Automation ──────────────────────────────────────────────────
    data class AutomationTrigger(val id: String, val name: String, val condition: () -> Boolean, val action: suspend () -> Unit, val enabled: Boolean = true)
    private val automations = mutableListOf<AutomationTrigger>()
    fun registerAutomation(t: AutomationTrigger) { automations.add(t); Log.i(TAG, "Automation: ${t.name}") }
    suspend fun checkAutomations() = automations.filter { it.enabled && it.condition() }.forEach { a ->
        Log.i(TAG, "Fired: ${a.name}")
        try { a.action() } catch (e: Exception) { Log.e(TAG, "Automation '\${a.name}': \${e.message}") }
    }
}