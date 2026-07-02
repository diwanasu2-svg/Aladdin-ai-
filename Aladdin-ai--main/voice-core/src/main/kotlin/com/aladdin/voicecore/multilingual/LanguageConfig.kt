package com.aladdin.voicecore.multilingual

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Multilingual configuration for the Voice Core engine.
 *
 * Supports Hindi (hi), Gujarati (gu), and English (en) with:
 *  - Per-language STT (Vosk / Whisper) model paths
 *  - Per-language Piper TTS voice model paths
 *  - Language-specific fallback chain
 *  - gTTS / Android TTS as secondary fallback for Gujarati
 *
 * Features: 1, 2, 5, 6, 7, 8, 9, 13
 */
@Parcelize
data class MultilingualConfig(

    // ─── Supported languages ──────────────────────────────────────────────────
    val supportedLanguages: List<String> = listOf(LANG_HINDI, LANG_GUJARATI, LANG_ENGLISH),
    val defaultLanguage: String = LANG_ENGLISH,

    // ─── STT Model paths (relative to context.filesDir) ──────────────────────
    val sttModelPathHindi: String = "models/vosk/vosk-model-hi-0.22",
    val sttModelPathGujarati: String = "models/vosk/vosk-model-gu-0.42",
    val sttModelPathEnglish: String = "models/vosk/vosk-model-small-en-us-0.15",

    // ─── Whisper server endpoint (if faster-whisper is used) ─────────────────
    val whisperServerUrl: String = "http://127.0.0.1:8765/transcribe",
    val whisperLanguageAutoDetect: Boolean = true,

    // ─── TTS Voice paths (relative to context.filesDir) ──────────────────────
    val ttsVoiceEnglish: String = "en_US-lessac-medium",
    val ttsVoiceHindi: String = "hi_IN-hindi_male-medium",
    val ttsVoiceGujarati: String = "gu_IN-cmu_indic-medium",

    // ─── TTS engine preferences per language ─────────────────────────────────
    val ttsEngineHindi: TtsEngine = TtsEngine.PIPER,
    val ttsEngineGujarati: TtsEngine = TtsEngine.PIPER,
    val ttsEngineEnglish: TtsEngine = TtsEngine.PIPER,

    // ─── TTS fallback chain  ─────────────────────────────────────────────────
    // If PIPER voice unavailable, fallback order is:
    //   Gujarati → Hindi → English
    //   Hindi    → English
    //   English  → ANDROID_TTS
    val fallbackToAndroidTts: Boolean = true,

    // ─── Language detection ───────────────────────────────────────────────────
    val detectionConfidenceThreshold: Float = 0.55f,
    val detectionHistoryWindow: Int = 5,
    val detectionHistoryWeight: Float = 0.2f,

    // ─── Voice preloading ─────────────────────────────────────────────────────
    val preloadVoicesOnStartup: Boolean = true,
    val preloadLanguages: List<String> = listOf(LANG_ENGLISH, LANG_HINDI, LANG_GUJARATI),

    // ─── Notifications ────────────────────────────────────────────────────────
    val notifyOnFallback: Boolean = true,

) : Parcelable {

    companion object {
        const val LANG_ENGLISH  = "en"
        const val LANG_HINDI    = "hi"
        const val LANG_GUJARATI = "gu"

        /** Fallback chain: if the requested language voice is unavailable, try these in order. */
        val FALLBACK_CHAIN: Map<String, List<String>> = mapOf(
            LANG_GUJARATI to listOf(LANG_GUJARATI, LANG_HINDI, LANG_ENGLISH),
            LANG_HINDI    to listOf(LANG_HINDI, LANG_ENGLISH),
            LANG_ENGLISH  to listOf(LANG_ENGLISH),
        )

        fun sttModelKey(language: String): String = "stt_vosk_$language"
        fun ttsVoiceKey(language: String): String = "tts_piper_$language"
    }

    /** Returns the preferred TTS engine for the given language. */
    fun ttsEngineFor(language: String): TtsEngine = when (language) {
        LANG_HINDI    -> ttsEngineHindi
        LANG_GUJARATI -> ttsEngineGujarati
        else          -> ttsEngineEnglish
    }

    /** Returns the Piper voice name for the given language. */
    fun ttsVoiceFor(language: String): String = when (language) {
        LANG_HINDI    -> ttsVoiceHindi
        LANG_GUJARATI -> ttsVoiceGujarati
        else          -> ttsVoiceEnglish
    }

    /** Returns the STT model path for the given language. */
    fun sttModelPathFor(language: String): String = when (language) {
        LANG_HINDI    -> sttModelPathHindi
        LANG_GUJARATI -> sttModelPathGujarati
        else          -> sttModelPathEnglish
    }

    /** Returns the fallback chain for the given language. */
    fun fallbackChainFor(language: String): List<String> =
        FALLBACK_CHAIN[language] ?: listOf(language, LANG_ENGLISH)
}

const val LANG_HINDI    = "hi"
const val LANG_GUJARATI = "gu"
const val LANG_ENGLISH  = "en"

/** TTS engine selection. */
enum class TtsEngine {
    PIPER,         // Piper (offline neural TTS) — preferred
    ANDROID_TTS,   // Android's built-in TTS (always available, lower quality)
    GTTS_HTTP,     // Google TTS via local HTTP proxy (requires network)
}

/** Language detection result. */
data class LanguageDetectionResult(
    val language: String,
    val confidence: Float,
    val method: String,
    val isMixed: Boolean = false,
    val fallbackUsed: Boolean = false,
) {
    val languageName: String get() = when (language) {
        LANG_HINDI    -> "Hindi"
        LANG_GUJARATI -> "Gujarati"
        LANG_ENGLISH  -> "English"
        else          -> language
    }
}

/** Events specific to multilingual operations. */
sealed class MultilingualEvent {
    data class LanguageDetected(val result: LanguageDetectionResult) : MultilingualEvent()
    data class TtsFallbackUsed(val requested: String, val actual: String, val reason: String) : MultilingualEvent()
    data class VoicePreloaded(val language: String) : MultilingualEvent()
    data class VoicePreloadFailed(val language: String, val reason: String) : MultilingualEvent()
}
