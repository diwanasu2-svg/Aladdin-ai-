package com.aladdin.app.mood

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MoodDetector — Item 69: Sentiment analysis and mood detection.
 * Implements keyword-based sentiment + pattern scoring with mood history.
 */
@Singleton
class MoodDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "MoodDetector" }

    enum class Mood { VERY_POSITIVE, POSITIVE, NEUTRAL, NEGATIVE, VERY_NEGATIVE, ANXIOUS, EXCITED, FRUSTRATED }

    data class MoodResult(
        val mood: Mood,
        val confidence: Float,
        val sentiment: Float,    // -1.0 to +1.0
        val emotions: Map<String, Float> = emptyMap()
    )

    private val history = ArrayDeque<MoodResult>(50)

    private val positiveWords = setOf("happy","great","excellent","wonderful","amazing","love","good","awesome","fantastic","joy","excited","pleased","delighted","perfect","brilliant","glad")
    private val negativeWords = setOf("sad","bad","terrible","horrible","awful","hate","angry","frustrated","upset","worried","anxious","depressed","miserable","annoyed","disappointed","stressed")
    private val anxiousWords  = setOf("worried","anxious","nervous","scared","afraid","panic","stress","overwhelmed","uncertain")
    private val excitedWords  = setOf("excited","thrilled","eager","amazing","wonderful","awesome","incredible","fantastic","wow")

    fun analyze(text: String): MoodResult {
        val lower = text.lowercase()
        val words = lower.split(Regex("\\W+")).filter { it.isNotBlank() }

        var positiveScore = 0f
        var negativeScore = 0f
        var anxiousScore  = 0f
        var excitedScore  = 0f

        for (word in words) {
            if (word in positiveWords) positiveScore += 1f
            if (word in negativeWords) negativeScore += 1f
            if (word in anxiousWords)  anxiousScore  += 1.5f
            if (word in excitedWords)  excitedScore  += 1.5f
        }

        // Normalize by word count
        val n = words.size.coerceAtLeast(1).toFloat()
        positiveScore /= n; negativeScore /= n; anxiousScore /= n; excitedScore /= n

        val sentiment = (positiveScore - negativeScore).coerceIn(-1f, 1f)
        val confidence = (positiveScore + negativeScore + anxiousScore + excitedScore)
            .coerceAtLeast(0.1f).coerceAtMost(1f)

        val mood = when {
            anxiousScore  > 0.05f -> Mood.ANXIOUS
            excitedScore  > 0.05f -> Mood.EXCITED
            sentiment     > 0.15f -> Mood.VERY_POSITIVE
            sentiment     > 0.05f -> Mood.POSITIVE
            sentiment     < -0.15f-> Mood.VERY_NEGATIVE
            sentiment     < -0.05f-> Mood.NEGATIVE
            negativeScore > 0.05f && positiveScore > 0.05f -> Mood.FRUSTRATED
            else                  -> Mood.NEUTRAL
        }

        val result = MoodResult(
            mood = mood, confidence = confidence.coerceAtMost(1f), sentiment = sentiment,
            emotions = mapOf("positive" to positiveScore, "negative" to negativeScore,
                             "anxious" to anxiousScore, "excited" to excitedScore)
        )
        history.addFirst(result)
        if (history.size > 50) history.removeLast()
        Log.d(TAG, "Mood: $mood sentiment=$sentiment confidence=$confidence")
        return result
    }

    fun getAdaptiveResponse(mood: Mood): String = when (mood) {
        Mood.VERY_POSITIVE -> "I can tell you're in a great mood! "
        Mood.POSITIVE      -> "Glad things are going well. "
        Mood.ANXIOUS       -> "I'm here to help. Take a breath — we'll figure this out together. "
        Mood.EXCITED       -> "Your enthusiasm is great! "
        Mood.FRUSTRATED    -> "I understand this is frustrating. Let me help make it easier. "
        Mood.NEGATIVE      -> "I'm sorry to hear that. How can I help? "
        Mood.VERY_NEGATIVE -> "That sounds really tough. I'm here for you. "
        Mood.NEUTRAL       -> ""
    }

    fun getMoodHistory(): List<MoodResult> = history.toList()
    fun getAverageSentiment(): Float = if (history.isEmpty()) 0f else history.map { it.sentiment }.average().toFloat()
}
