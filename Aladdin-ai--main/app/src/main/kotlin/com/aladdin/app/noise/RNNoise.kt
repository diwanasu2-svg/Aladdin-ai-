package com.aladdin.app.noise

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * RNNoise — Item 30: Native RNNoise noise suppression.
 * JNI path: librnnoise.so → real neural noise suppression.
 * Fallback: spectral gate with adaptive noise floor (no heuristics beyond gating).
 */
@Singleton
class RNNoise @Inject constructor() {
    companion object {
        private const val TAG = "RNNoise"
        private const val RNNOISE_FRAME = 480
        private const val UPSAMPLE = 3  // 16kHz → 48kHz

        private var nativeLoaded = false
        init {
            nativeLoaded = try {
                System.loadLibrary("rnnoise")
                Log.i(TAG, "RNNoise native loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "librnnoise.so not found — spectral gate fallback")
                false
            }
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeProcess(state: Long, frame: FloatArray): Float
    private external fun nativeDestroy(state: Long)

    private var state = 0L
    private var lastVad = 0f
    private var noiseFloor = 1000f

    fun initialize(): Boolean {
        return if (nativeLoaded) { state = nativeCreate(); (state != 0L).also { Log.i(TAG, "RNNoise init: \$it") } }
        else true
    }

    fun release() { if (state != 0L) { try { nativeDestroy(state) } catch (_: Exception) {}; state = 0L } }

    fun processFrame(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        return if (nativeLoaded && state != 0L) processNative(input) else processFallback(input)
    }

    fun getLastVadConfidence() = lastVad

    private fun processNative(input: ShortArray): ShortArray {
        val up = FloatArray(input.size * UPSAMPLE) { input[it / UPSAMPLE].toFloat() / 32768f }
        val result = up.copyOf()
        var vadSum = 0f; var frames = 0; var offset = 0
        while (offset + RNNOISE_FRAME <= up.size) {
            val frame = up.copyOfRange(offset, offset + RNNOISE_FRAME)
            vadSum += nativeProcess(state, frame)
            frame.copyInto(result, offset); offset += RNNOISE_FRAME; frames++
        }
        lastVad = if (frames > 0) vadSum / frames else 0f
        return ShortArray(input.size) { i -> (result[i * UPSAMPLE] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    private fun processFallback(input: ShortArray): ShortArray {
        val rms = sqrt(input.sumOf { it.toLong() * it }.toDouble() / input.size).toFloat()
        if (rms < noiseFloor * 2f) noiseFloor = noiseFloor * 0.97f + rms * 0.03f
        lastVad = when { rms < noiseFloor * 1.2f -> 0f; rms > noiseFloor * 4f -> 1f; else -> (rms - noiseFloor) / (3f * noiseFloor) }.coerceIn(0f, 1f)
        if (rms < noiseFloor * 1.5f) return ShortArray(input.size) { (input[it] * 0.15f).toInt().toShort() }
        val snr = (rms - noiseFloor) / noiseFloor
        val gain = snr / (snr + 1f)
        return ShortArray(input.size) { (input[it] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    val isNativeAvailable get() = nativeLoaded
}