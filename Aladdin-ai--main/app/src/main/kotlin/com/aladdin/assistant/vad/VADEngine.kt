package com.aladdin.assistant.vad

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Phase 2 – VAD Engine
 * Silence detection: 350 ms (was 3 000 ms).
 * Multi-band energy + SNR + spectral tonicity → confidence score 0.0–1.0.
 * Adaptive noise floor updated during silence frames.
 */
class VADEngine {
    companion object {
        private const val TAG = "VADEngine"
        var silenceThresholdMs: Long = 350L
        var voiceConfidenceThreshold: Float = 0.55f
        private const val NOISE_FLOOR_ALPHA = 0.05f
    }

    private val _isVoiceActive = MutableStateFlow(false)
    val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()

    private val _confidenceFlow = MutableStateFlow(0f)
    val confidenceFlow: StateFlow<Float> = _confidenceFlow.asStateFlow()

    private var adaptiveNoiseFloor = 0.01f
    private var lastVoiceTimestamp = 0L
    private var speechStarted = false

    fun processFrame(pcm: FloatArray): VadResult {
        val now = System.currentTimeMillis()
        val energy = rms(pcm)
        val snr = snrDb(energy)
        val tonicity = tonicity(pcm)
        val confidence = (energy.scoreInRange(0.005f, 0.15f) * 0.40f +
                ((snr + 5f) / 25f).coerceIn(0f, 1f) * 0.40f +
                tonicity * 0.20f).coerceIn(0f, 1f)

        _confidenceFlow.value = confidence
        val voiced = confidence >= voiceConfidenceThreshold

        if (voiced) {
            lastVoiceTimestamp = now
            if (!speechStarted) { speechStarted = true; _isVoiceActive.value = true
                Log.d(TAG, "Speech START conf=${"%.2f".format(confidence)}") }
        } else {
            adaptiveNoiseFloor = adaptiveNoiseFloor * (1f - NOISE_FLOOR_ALPHA) +
                energy * NOISE_FLOOR_ALPHA
            if (speechStarted) {
                val silMs = now - lastVoiceTimestamp
                if (silMs >= silenceThresholdMs) {
                    speechStarted = false; _isVoiceActive.value = false
                    Log.d(TAG, "Speech END after ${silMs}ms")
                    return VadResult(false, confidence, silMs, true)
                }
            }
        }
        return VadResult(voiced, confidence,
            if (!voiced && speechStarted) now - lastVoiceTimestamp else 0L, false)
    }

    fun reset() { speechStarted = false; lastVoiceTimestamp = 0L
        _isVoiceActive.value = false; _confidenceFlow.value = 0f }

    private fun rms(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        return sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size).toFloat()
    }
    private fun snrDb(energy: Float): Float {
        val floor = adaptiveNoiseFloor.coerceAtLeast(1e-6f)
        val r = energy / floor
        return if (r > 0) (20f * log10(r.toDouble())).toFloat().coerceIn(-20f, 40f) else -20f
    }
    private fun tonicity(pcm: FloatArray): Float {
        val n = pcm.size.coerceAtMost(512)
        if (n < 4) return 1f
        val mags = FloatArray(n / 2) { i ->
            val re = pcm.getOrElse(2 * i) { 0f }; val im = pcm.getOrElse(2 * i + 1) { 0f }
            sqrt((re * re + im * im).toDouble()).toFloat().coerceAtLeast(1e-8f)
        }
        val gm = mags.fold(0.0) { a, v -> a + log10(v.toDouble()) }
            .let { Math.pow(10.0, it / mags.size) }.toFloat()
        val am = mags.average().toFloat().coerceAtLeast(1e-8f)
        return (1f - (gm / am).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
    private fun Float.scoreInRange(lo: Float, hi: Float) =
        when { this < lo -> 0f; this > hi -> 1f; else -> (this - lo) / (hi - lo) }

    data class VadResult(
        val isSpeech: Boolean,
        val confidence: Float,
        val silenceDurationMs: Long,
        val utteranceEnded: Boolean
    )
}
