package com.aladdin.app.intelligence

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 11 Item 6: Mood-Based Response Adapter ─────────────────────────────

@Singleton
class MoodBasedResponseAdapter @Inject constructor(
    private val intelligenceEngine: IntelligenceEngine
) {
    companion object { private const val TAG = "MoodAdapter" }

    data class AdaptedResponse(
        val text: String,
        val tone: Tone,
        val detectedEmotion: IntelligenceEngine.Emotion,
        val emotionConfidence: Float
    )

    enum class Tone { EMPATHETIC, ENCOURAGING, CALM, PROFESSIONAL, PLAYFUL, SUPPORTIVE }

    // ── Detect emotion and adapt response ────────────────────────────────────
    fun adaptResponse(userInput: String, rawResponse: String): AdaptedResponse {
        val emotionState = intelligenceEngine.detectEmotion(userInput)
        val tone = selectTone(emotionState)
        val adapted = applyTone(rawResponse, tone, emotionState)
        Log.d(TAG, "Emotion=${emotionState.primary} Tone=$tone")
        return AdaptedResponse(adapted, tone, emotionState.primary, emotionState.confidence)
    }

    // ── Tone selection based on emotion ──────────────────────────────────────
    private fun selectTone(state: IntelligenceEngine.EmotionState): Tone {
        return when (state.primary) {
            IntelligenceEngine.Emotion.SAD,
            IntelligenceEngine.Emotion.ANXIOUS      -> Tone.EMPATHETIC
            IntelligenceEngine.Emotion.FRUSTRATED,
            IntelligenceEngine.Emotion.ANGRY        -> Tone.CALM
            IntelligenceEngine.Emotion.HAPPY,
            IntelligenceEngine.Emotion.EXCITED      -> Tone.PLAYFUL
            IntelligenceEngine.Emotion.CONFUSED     -> Tone.SUPPORTIVE
            IntelligenceEngine.Emotion.NEUTRAL      -> Tone.PROFESSIONAL
        }
    }

    // ── Apply tone transformations to response ────────────────────────────────
    private fun applyTone(
        response: String,
        tone: Tone,
        state: IntelligenceEngine.EmotionState
    ): String {
        val prefix = when (tone) {
            Tone.EMPATHETIC   -> when (state.primary) {
                IntelligenceEngine.Emotion.SAD     -> "I understand this is difficult. "
                IntelligenceEngine.Emotion.ANXIOUS -> "Take a breath — I'm here to help. "
                else                               -> ""
            }
            Tone.CALM         -> "Let me help you with this calmly. "
            Tone.PLAYFUL      -> ""  // No prefix, just more energetic phrasing
            Tone.SUPPORTIVE   -> "No worries — let me explain this clearly. "
            Tone.ENCOURAGING  -> "You're doing great! "
            Tone.PROFESSIONAL -> ""
        }

        val adjusted = when (tone) {
            Tone.PLAYFUL   -> response.replace(".", "!").replace("Certainly.", "Sure!")
            Tone.CALM      -> response
                .replace("!", ".")
                .replace("Right away!", "Of course.")
                .replace("Immediately!", "Let me work on that.")
            Tone.EMPATHETIC -> response.replace("Here is", "Here's what I found for you —")
            else            -> response
        }

        val suffix = when {
            state.primary == IntelligenceEngine.Emotion.FRUSTRATED && state.confidence > 0.5f ->
                " Is there anything else I can do to help?"
            state.primary == IntelligenceEngine.Emotion.ANXIOUS ->
                " Let me know if you need me to explain anything further."
            else -> ""
        }

        return "$prefix$adjusted$suffix".trim()
    }

    // ── Generate an empathetic acknowledgment before answering ────────────────
    fun generateAcknowledgment(emotion: IntelligenceEngine.Emotion): String? {
        return when (emotion) {
            IntelligenceEngine.Emotion.SAD         -> "I can hear that things are tough right now."
            IntelligenceEngine.Emotion.FRUSTRATED  -> "I understand your frustration."
            IntelligenceEngine.Emotion.ANXIOUS     -> "It's okay to feel stressed. I'm here."
            IntelligenceEngine.Emotion.CONFUSED    -> "That does sound confusing. Let me help."
            IntelligenceEngine.Emotion.EXCITED     -> "That's exciting!"
            IntelligenceEngine.Emotion.HAPPY       -> null  // No preamble needed
            IntelligenceEngine.Emotion.NEUTRAL     -> null
            IntelligenceEngine.Emotion.ANGRY       -> "I hear you."
        }
    }
}
