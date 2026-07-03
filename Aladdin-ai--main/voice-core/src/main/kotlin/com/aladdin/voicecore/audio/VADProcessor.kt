package com.aladdin.voicecore.audio

import android.util.Log
import com.aladdin.voicecore.BuildConfig
import com.aladdin.voicecore.models.VoiceCoreConfig

/**
 * WebRTC Voice Activity Detector.
 *
 * Wraps the WebRTC VAD via JNI. Falls back to energy-based VAD
 * when native library is unavailable.
 *
 * Aggressiveness:
 *   0 = least aggressive  3 = most aggressive
 */
class VADProcessor(private val config: VoiceCoreConfig) {

    companion object {
        private const val TAG = "VADProcessor"

        init {
            try {
                System.loadLibrary("webrtc_vad")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "WebRTC VAD native library not found – using energy-based fallback")
            }
        }

        @JvmStatic private external fun nativeCreate(aggressiveness: Int): Long
        @JvmStatic private external fun nativeProcess(handle: Long, sampleRate: Int, frame: ShortArray): Boolean
        @JvmStatic private external fun nativeDestroy(handle: Long)

        private const val ENERGY_THRESHOLD = 500.0
    }

    private var nativeHandle: Long = 0L
    private var useNative: Boolean = false

    // Fix 12: @Volatile so multi-threaded access is visible across cores
    @Volatile private var speechRun = 0
    private val speechRunRequired = 2

    fun init() {
        reset() // Fix 12: reset speechRun on every init
        try {
            nativeHandle = nativeCreate(config.vadAggressiveness)
            useNative = nativeHandle != 0L
            if (BuildConfig.DEBUG) Log.i(TAG, "WebRTC VAD initialised (native=$useNative, aggressiveness=${config.vadAggressiveness})")
        } catch (e: Exception) {
            Log.w(TAG, "VAD native init failed: ${e.message} – using energy fallback")
            useNative = false
        }
    }

    fun isSpeech(frame: ShortArray): Boolean {
        val result = if (useNative && nativeHandle != 0L) {
            try {
                nativeProcess(nativeHandle, config.sampleRateHz, frame)
            } catch (e: Exception) {
                Log.w(TAG, "VAD native process error, using energy fallback: ${e.message}")
                energyVAD(frame)
            }
        } else {
            energyVAD(frame)
        }

        if (result) speechRun++ else speechRun = 0
        return speechRun >= speechRunRequired
    }

    private fun energyVAD(frame: ShortArray): Boolean {
        if (frame.isEmpty()) return false
        val rms = Math.sqrt(frame.sumOf { it.toDouble() * it.toDouble() } / frame.size)
        return rms > ENERGY_THRESHOLD
    }

    fun reset() {
        speechRun = 0
    }

    fun release() {
        if (useNative && nativeHandle != 0L) {
            try { nativeDestroy(nativeHandle) } catch (_: Exception) {}
            nativeHandle = 0L
        }
    }
}
