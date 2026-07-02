package com.aladdin.assistant.noise

import android.util.Log

/**
 * Phase 1 fix item 1.9 — JNI wrapper for librnnoise.so with graceful fallback.
 *
 * Loads librnnoise.so for arm64-v8a / armeabi-v7a.
 * Falls back to no-op pass-through if native library unavailable.
 */
object RNNoiseJniWrapper {
    private const val TAG = "RNNoiseJniWrapper"

    val isLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("rnnoise")
            loaded = true
            Log.i(TAG, "librnnoise.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "librnnoise.so not found — noise suppression disabled: ${e.message}")
        }
        isLoaded = loaded
    }

    external fun create(): Long
    external fun processFrame(statePtr: Long, pcm: FloatArray): Float
    external fun destroy(statePtr: Long)

    /** Safe wrapper — passes audio through unchanged if library not loaded. */
    fun suppressNoise(statePtr: Long, pcm: FloatArray): FloatArray {
        if (!isLoaded || statePtr == 0L) return pcm
        return try {
            processFrame(statePtr, pcm)
            pcm
        } catch (e: Exception) {
            Log.e(TAG, "RNNoise processing failed: ${e.message}")
            pcm
        }
    }
}
