package com.aladdin.voicecore.models

/**
 * Events emitted by the Voice Core pipeline components.
 */
sealed class VoiceCoreEvent {

    /** Wake word was detected with given keyword and confidence score. */
    data class WakeWordDetected(val keyword: String, val confidence: Float) : VoiceCoreEvent()

    /** Speech transcript (partial or final). */
    data class Transcript(val text: String, val isFinal: Boolean) : VoiceCoreEvent()

    /** Speech segment ended (silence detected). */
    object SpeechEnded : VoiceCoreEvent()

    /** TTS playback completed. */
    object TTSComplete : VoiceCoreEvent()

    /** Mic recovered after error. */
    object MicRecovered : VoiceCoreEvent()

    /** Engine entered sleep mode after idle timeout. */
    object SleepModeEntered : VoiceCoreEvent()

    /** Engine exited sleep mode (e.g. wake word detected). */
    object SleepModeExited : VoiceCoreEvent()

    /** Audio output device changed (headset plug/unplug). */
    data class AudioDeviceChanged(val deviceName: String) : VoiceCoreEvent()

    /** An error occurred. */
    data class Error(
        val code: ErrorCode,
        val message: String,
        val cause: Throwable? = null
    ) : VoiceCoreEvent()
}

/** Error codes for voice pipeline errors. */
enum class ErrorCode {
    MIC_UNAVAILABLE,
    WAKE_WORD_MODEL_NOT_FOUND,
    STT_MODEL_NOT_FOUND,
    TTS_MODEL_NOT_FOUND,
    AUDIO_FOCUS_LOST,
    PERMISSION_DENIED,
    UNKNOWN
}
