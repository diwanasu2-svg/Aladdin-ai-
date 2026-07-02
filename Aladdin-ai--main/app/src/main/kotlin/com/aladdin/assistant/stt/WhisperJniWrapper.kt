package com.aladdin.assistant.stt

import android.util.Log

/**
 * Phase 1 fix item 1.8 — JNI wrapper for libwhisper.so with graceful fallback.
 *
 * Loads libwhisper.so for arm64-v8a / armeabi-v7a.
 * Falls back to Android SpeechRecognizer if native library unavailable.
 */
object WhisperJniWrapper {
    private const val TAG = "WhisperJniWrapper"

    val isLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("whisper")
            loaded = true
            Log.i(TAG, "libwhisper.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libwhisper.so not found — STT will use Android fallback: ${e.message}")
        }
        isLoaded = loaded
    }

    // Native function declarations — only callable if isLoaded == true
    external fun init(modelPath: String): Long
    external fun transcribePartial(ctxPtr: Long, pcm: FloatArray, nSamples: Int): String
    external fun transcribeFull(ctxPtr: Long, pcm: FloatArray, nSamples: Int): String
    external fun free(ctxPtr: Long)
    external fun getVersion(): String
}
