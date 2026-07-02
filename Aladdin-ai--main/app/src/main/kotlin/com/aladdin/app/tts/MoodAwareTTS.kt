package com.aladdin.app.tts

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MoodAwareTTS — Item 33: Mood-based TTS voice modulation.
 * Detects emotional tone and adjusts speed/pitch automatically.
 */
@Singleton
class MoodAwareTTS @Inject constructor(
    @ApplicationContext private val context: Context,
    private val piperTts: PiperTTS
) {
    companion object { private const val TAG = "MoodAwareTTS" }

    enum class Mood { NEUTRAL, HAPPY, SAD, EXCITED, CALM, URGENT, APOLOGETIC, CURIOUS }
    data class VoiceParams(val speed: Float = 1f, val pitch: Float = 1f, val vol: Float = 1f)

    private val profiles = mapOf(
        Mood.NEUTRAL to VoiceParams(1.00f, 1.00f, 1.00f),
        Mood.HAPPY to VoiceParams(1.10f, 1.10f, 1.05f),
        Mood.SAD to VoiceParams(0.85f, 0.92f, 0.90f),
        Mood.EXCITED to VoiceParams(1.20f, 1.15f, 1.10f),
        Mood.CALM to VoiceParams(0.90f, 0.97f, 0.95f),
        Mood.URGENT to VoiceParams(1.15f, 1.05f, 1.10f),
        Mood.APOLOGETIC to VoiceParams(0.92f, 0.95f, 0.93f),
        Mood.CURIOUS to VoiceParams(1.05f, 1.07f, 1.00f)
    )

    private val keywords = mapOf(
        Mood.HAPPY to listOf("great","wonderful","excellent","amazing","love","happy","celebrate","congratulations"),
        Mood.SAD to listOf("sorry","unfortunately","regret","sad","difficult","failed","error","apologize"),
        Mood.EXCITED to listOf("exciting","awesome","incredible","wow","yes","perfect","brilliant"),
        Mood.CALM to listOf("relax","calm","peaceful","gentle","breathe","quiet","serene"),
        Mood.URGENT to listOf("urgent","immediately","emergency","critical","danger","warning","quickly","asap"),
        Mood.APOLOGETIC to listOf("i'm sorry","i apologize","my mistake","my bad","i was wrong","pardon"),
        Mood.CURIOUS to listOf("interesting","wonder","curious","intriguing","perhaps","maybe","could be")
    )

    fun detectMood(text: String): Mood {
        val lower = text.lowercase()
        if (text.count { it == '!' } >= 2) return Mood.EXCITED
        val scores = mutableMapOf<Mood, Int>()
        keywords.forEach { (mood, kws) -> val s = kws.count { lower.contains(it) }; if (s > 0) scores[mood] = s }
        return scores.maxByOrNull { it.value }?.key ?: Mood.NEUTRAL
    }

    fun applyMoodParams(mood: Mood) {
        val p = profiles[mood] ?: profiles[Mood.NEUTRAL]!!
        piperTts.setSpeed(p.speed); piperTts.setPitch(p.pitch)
        Log.d(TAG, "Voice: \$mood speed=\${p.speed} pitch=\${p.pitch}")
    }

    fun prepareSpeech(text: String): Mood { val m = detectMood(text); applyMoodParams(m); return m }
    fun overrideMood(mood: Mood) = applyMoodParams(mood)
    fun getVoiceParams(mood: Mood) = profiles[mood] ?: profiles[Mood.NEUTRAL]!!
}