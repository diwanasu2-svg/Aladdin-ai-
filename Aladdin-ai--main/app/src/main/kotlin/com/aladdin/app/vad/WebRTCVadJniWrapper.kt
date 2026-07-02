package com.aladdin.app.vad

import android.util.Log

/**
 * Phase 1 fix item 1.10 — JNI wrapper for libwebrtc_vad.so with graceful fallback.
 *
 * Loads libwebrtc_vad.so for arm64-v8a / armeabi-v7a.
 * Falls back to energy-based VAD if native library unavailable.
 */
object WebRTCVadJniWrapper {
    private const val TAG = "WebRTCVadJniWrapper"

    val isLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("webrtc_vad")
            loaded = true
            Log.i(TAG, "libwebrtc_vad.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libwebrtc_vad.so not found — using energy-based VAD fallback: ${e.message}")
        }
        isLoaded = loaded
    }

    external fun create(): Long
    external fun init(instancePtr: Long, sampleRateHz: Int): Int
    external fun process(instancePtr: Long, frameLengthSamples: Int, audioFrame: ShortArray): Int
    external fun free(instancePtr: Long)

    /**
     * Safe isSpeech wrapper — falls back to energy heuristic if library not loaded.
     *
     * @return true if speech detected (native or energy-based fallback)
     */
    fun isSpeech(instancePtr: Long, audioFrame: ShortArray, energyThreshold: Float = 500f): Boolean {
        if (!isLoaded || instancePtr == 0L) {
            return energyFallback(audioFrame, energyThreshold)
        }
        return try {
            process(instancePtr, audioFrame.size, audioFrame) == 1
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC VAD failed, using energy fallback: ${e.message}")
            energyFallback(audioFrame, energyThreshold)
        }
    }

    private fun energyFallback(pcm: ShortArray, threshold: Float): Boolean {
        if (pcm.isEmpty()) return false
        val rms = Math.sqrt(pcm.sumOf { it.toLong() * it.toLong() }.toDouble() / pcm.size).toFloat()
        return rms > threshold
    }
}
