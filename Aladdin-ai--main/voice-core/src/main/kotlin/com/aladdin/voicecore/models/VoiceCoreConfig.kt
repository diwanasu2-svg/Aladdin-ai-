package com.aladdin.voicecore.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Master configuration for the Voice Core engine.
 * All fields have sensible defaults and can be overridden at runtime.
 */
@Parcelize
data class VoiceCoreConfig(

    // ─── Wake Word ───────────────────────────────────────────────────────────
    val wakeWords: List<String> = listOf("aladdin", "jarvis", "computer"),
    val wakeWordEngine: WakeWordEngine = WakeWordEngine.VOSK,
    val wakeWordSensitivity: Float = 0.7f,          // 0.3 – 0.9
    val wakeWordConfidenceThreshold: Float = 0.7f,  // false-trigger reduction
    val wakeWordIdleTimeoutSec: Int = 30,           // auto-timeout after idle
    val porcupineAccessKey: String = "",            // required if engine = PORCUPINE

    // ─── Continuous Listening ─────────────────────────────────────────────────
    val silenceTimeoutMs: Long = 3_000,             // 3-second silence → end utterance
    val sleepAfterIdleMs: Long = 300_000,           // 5 minutes → sleep mode
    val conversationModeEnabled: Boolean = false,   // stay awake after each response
    val autoResumeAfterTTS: Boolean = true,

    // ─── Audio Pipeline ───────────────────────────────────────────────────────
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val frameSizeMs: Int = 30,                     // WebRTC VAD frame size
    val enableVAD: Boolean = true,
    val enableNoiseSuppression: Boolean = true,
    val enableAEC: Boolean = true,                  // Acoustic Echo Cancellation
    val enableAGC: Boolean = true,                  // Automatic Gain Control
    val vadAggressiveness: Int = 2,                 // 0-3 (WebRTC VAD)

    // ─── STT ─────────────────────────────────────────────────────────────────
    val sttModelPath: String = "models/vosk-model",
    val sttTargetLatencyMs: Int = 500,
    val sttBeamSize: Int = 5,
    val sttLanguage: String = "en",

    // ─── TTS ─────────────────────────────────────────────────────────────────
    val ttsModelPath: String = "models/piper",
    val ttsVoice: String = "en_US-lessac-medium",
    val ttsSpeakingRate: Float = 1.0f,
    val ttsStreamingEnabled: Boolean = true,        // token-by-token speaking

    // ─── Battery / Performance ────────────────────────────────────────────────
    val batterySaverEnabled: Boolean = true,
    val maxCpuPercent: Int = 5                      // target always-on CPU budget

) : Parcelable {

    enum class WakeWordEngine { VOSK, PORCUPINE }

    companion object {
        /** Returns a config preset optimised for battery life. */
        fun batterySaver() = VoiceCoreConfig(
            vadAggressiveness = 3,
            wakeWordSensitivity = 0.5f,
            batterySaverEnabled = true
        )

        /** Returns a config preset optimised for low-latency responses. */
        fun lowLatency() = VoiceCoreConfig(
            frameSizeMs = 20,
            sttTargetLatencyMs = 300,
            wakeWordSensitivity = 0.85f
        )
    }
}

/** Events emitted by the Voice Core engine. */
sealed class VoiceCoreEvent {
    data class WakeWordDetected(val keyword: String, val confidence: Float) : VoiceCoreEvent()
    data class SpeechStarted(val timestampMs: Long = System.currentTimeMillis()) : VoiceCoreEvent()
    data class SpeechEnded(val timestampMs: Long = System.currentTimeMillis()) : VoiceCoreEvent()
    data class Transcript(val text: String, val isFinal: Boolean) : VoiceCoreEvent()
    data class TTSChunk(val text: String, val audioBytes: ByteArray) : VoiceCoreEvent()
    object TTSComplete : VoiceCoreEvent()
    object SleepModeEntered : VoiceCoreEvent()
    object SleepModeExited : VoiceCoreEvent()
    data class Error(val code: ErrorCode, val message: String, val cause: Throwable? = null) : VoiceCoreEvent()
    object MicRecovered : VoiceCoreEvent()
    data class AudioDeviceChanged(val deviceName: String) : VoiceCoreEvent()
}

enum class ErrorCode {
    MIC_UNAVAILABLE,
    MIC_PERMISSION_DENIED,
    WAKE_WORD_MODEL_NOT_FOUND,
    STT_MODEL_NOT_FOUND,
    TTS_MODEL_NOT_FOUND,
    AUDIO_FOCUS_LOST,
    PIPELINE_OVERFLOW,
    UNKNOWN
}

/** State machine for the Voice Core engine. */
enum class VoiceCoreState {
    IDLE,
    LISTENING_FOR_WAKE_WORD,
    CAPTURING_SPEECH,
    PROCESSING_STT,
    SPEAKING_TTS,
    SLEEP,
    ERROR
}
