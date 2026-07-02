package com.aladdin.app.intelligence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 11 Item 7: Personalized Behavior Engine ────────────────────────────

@Singleton
class PersonalizedBehaviorEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG  = "PersonalizedBehavior"
        private const val PREF = "behavior_profile"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class UserProfile(
        val preferredResponseLength: ResponseLength = ResponseLength.MEDIUM,
        val communicationStyle: CommunicationStyle  = CommunicationStyle.FRIENDLY,
        val preferMetric: Boolean                   = true,
        val preferredLanguage: String               = "en",
        val topicsOfInterest: List<String>          = emptyList(),
        val averageSessionDuration: Long            = 0L,
        val totalInteractions: Int                  = 0,
        val feedbackScore: Float                    = 0f,
        val lastActiveMs: Long                      = 0L
    )

    enum class ResponseLength { BRIEF, MEDIUM, DETAILED }
    enum class CommunicationStyle { FORMAL, FRIENDLY, TECHNICAL, CASUAL }

    data class BehaviorPattern(
        val pattern: String,
        val frequency: Int,
        val lastSeenMs: Long,
        val context: String
    )

    // ── Load current user profile ─────────────────────────────────────────────
    fun loadProfile(): UserProfile {
        return try {
            val json = prefs.getString("profile", null) ?: return UserProfile()
            val obj  = JSONObject(json)
            UserProfile(
                preferredResponseLength = ResponseLength.valueOf(obj.optString("responseLength", "MEDIUM")),
                communicationStyle      = CommunicationStyle.valueOf(obj.optString("commStyle", "FRIENDLY")),
                preferMetric            = obj.optBoolean("metric", true),
                preferredLanguage       = obj.optString("lang", "en"),
                topicsOfInterest        = obj.optJSONArray("topics")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                averageSessionDuration  = obj.optLong("avgSessionMs", 0),
                totalInteractions       = obj.optInt("totalInteractions", 0),
                feedbackScore           = obj.optDouble("feedbackScore", 0.0).toFloat(),
                lastActiveMs            = obj.optLong("lastActiveMs", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "loadProfile failed: ${e.message}")
            UserProfile()
        }
    }

    // ── Record user interaction + update profile ──────────────────────────────
    suspend fun recordInteraction(
        userInput: String,
        responseLength: Int,
        positiveFeedback: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val profile = loadProfile()
            val newTotal = profile.totalInteractions + 1

            // Update feedback score with exponential moving average
            val newScore = if (positiveFeedback != null) {
                val fb = if (positiveFeedback) 1f else 0f
                if (profile.feedbackScore == 0f) fb
                else profile.feedbackScore * 0.9f + fb * 0.1f
            } else profile.feedbackScore

            // Update preferred response length based on usage
            val avgLength = (profile.averageSessionDuration * (newTotal - 1) + responseLength) / newTotal

            // Extract topic from input
            val topics = detectTopics(userInput, profile.topicsOfInterest)

            val updated = profile.copy(
                totalInteractions      = newTotal,
                feedbackScore          = newScore,
                averageSessionDuration = avgLength,
                topicsOfInterest       = topics,
                lastActiveMs           = System.currentTimeMillis()
            )
            saveProfile(updated)
            Log.d(TAG, "Profile updated: interactions=$newTotal score=$newScore")
        } catch (e: Exception) {
            Log.e(TAG, "recordInteraction failed: ${e.message}")
        }
    }

    // ── Personalize a response based on profile ───────────────────────────────
    fun personalizeResponse(raw: String, profile: UserProfile = loadProfile()): String {
        return when (profile.communicationStyle) {
            CommunicationStyle.FORMAL    -> raw.replace("Sure!", "Certainly.").replace("Hey", "Hello")
            CommunicationStyle.CASUAL    -> raw.replace("Certainly.", "Sure!").replace("Hello", "Hey")
            CommunicationStyle.TECHNICAL -> raw
            CommunicationStyle.FRIENDLY  -> if (!raw.contains("!")) "$raw 😊" else raw
        }.let { response ->
            when (profile.preferredResponseLength) {
                ResponseLength.BRIEF    -> response.split(". ").take(2).joinToString(". ").trimEnd('.') + "."
                ResponseLength.DETAILED -> response
                ResponseLength.MEDIUM   -> response.split(". ").take(4).joinToString(". ").trimEnd('.') + "."
            }
        }
    }

    // ── Preference learning from explicit feedback ────────────────────────────
    suspend fun updatePreference(key: String, value: Any) = withContext(Dispatchers.IO) {
        try {
            val profile = loadProfile()
            val updated = when (key) {
                "response_length"   -> profile.copy(preferredResponseLength = ResponseLength.valueOf(value.toString()))
                "comm_style"        -> profile.copy(communicationStyle = CommunicationStyle.valueOf(value.toString()))
                "prefer_metric"     -> profile.copy(preferMetric = value as Boolean)
                "language"          -> profile.copy(preferredLanguage = value.toString())
                else -> profile
            }
            saveProfile(updated)
            Log.i(TAG, "Preference updated: $key = $value")
        } catch (e: Exception) {
            Log.e(TAG, "updatePreference failed: ${e.message}")
        }
    }

    private fun detectTopics(input: String, existing: List<String>): List<String> {
        val topicKeywords = mapOf(
            "weather"  to listOf("weather", "rain", "temperature", "forecast"),
            "news"     to listOf("news", "headline", "article", "latest"),
            "music"    to listOf("music", "song", "play", "artist"),
            "tech"     to listOf("code", "program", "app", "software", "ai", "computer"),
            "health"   to listOf("workout", "exercise", "calories", "health", "fitness"),
            "travel"   to listOf("flight", "hotel", "trip", "travel", "navigate")
        )
        val detected = mutableSetOf<String>()
        detected.addAll(existing)
        val lower = input.lowercase()
        topicKeywords.forEach { (topic, kws) ->
            if (kws.any { lower.contains(it) }) detected.add(topic)
        }
        return detected.take(10).toList()
    }

    private fun saveProfile(profile: UserProfile) {
        val json = JSONObject().apply {
            put("responseLength",     profile.preferredResponseLength.name)
            put("commStyle",          profile.communicationStyle.name)
            put("metric",             profile.preferMetric)
            put("lang",               profile.preferredLanguage)
            put("totalInteractions",  profile.totalInteractions)
            put("feedbackScore",      profile.feedbackScore.toDouble())
            put("avgSessionMs",       profile.averageSessionDuration)
            put("lastActiveMs",       profile.lastActiveMs)
            val topicsArr = org.json.JSONArray()
            profile.topicsOfInterest.forEach { topicsArr.put(it) }
            put("topics", topicsArr)
        }
        prefs.edit().putString("profile", json.toString()).apply()
    }
}
