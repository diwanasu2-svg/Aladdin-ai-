package com.aladdin.voicecore.audio

import android.util.Log
import com.aladdin.voicecore.models.VoiceCoreConfig

/**
 * RNNoise-based noise suppression wrapper.
 *
 * RNNoise processes 10ms frames at 48kHz internally. This wrapper resamples
 * from 16kHz → 48kHz, runs RNNoise, then downsamples back to 16kHz.
 *
 * Native JNI API expected:
 *   - nativeCreate(): Long
 *   - nativeProcess(handle: Long, frame: FloatArray): FloatArray  (480 floats at 48kHz)
 *   - nativeDestroy(handle: Long)
 *
 * Falls back to a basic spectral-subtraction approach if native lib is absent.
 */
class NoiseProcessor(private val config: VoiceCoreConfig) {

    companion object {
        private const val TAG = "NoiseProcessor"
        private const val RNNOISE_FRAME_SIZE = 480  // 10ms @ 48kHz
        private const val RNNOISE_SAMPLE_RATE = 48_000

        init {
            try {
                System.loadLibrary("rnnoise")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "RNNoise native library not found – noise suppression disabled")
            }
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeProcess(handle: Long, frame: FloatArray): FloatArray
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }

    private var nativeHandle: Long = 0L
    private var useNative: Boolean = false

    // Noise floor estimation for software fallback
    private val noiseFloor = FloatArray(RNNOISE_FRAME_SIZE)
    private var noiseFloorInit = false

    fun init() {
        try {
            nativeHandle = nativeCreate()
            useNative = nativeHandle != 0L
            Log.i(TAG, "RNNoise initialised (native=$useNative)")
        } catch (e: Exception) {
            Log.w(TAG, "RNNoise init failed: ${e.message}")
            useNative = false
        }
    }

    /**
     * Processes a 16kHz PCM frame through RNNoise.
     * Returns the denoised frame or null if processing should be skipped.
     */
    fun process(frame: ShortArray): ShortArray? {
        if (!config.enableNoiseSuppression) return frame
        return try {
            if (useNative && nativeHandle != 0L) {
                processNative(frame)
            } else {
                processSoftware(frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Noise processing error: ${e.message}")
            frame // pass through on error
        }
    }

    private fun processNative(frame: ShortArray): ShortArray {
        // Upsample 16kHz → 48kHz (simple 3x repeat)
        val upsampled = FloatArray(frame.size * 3)
        frame.forEachIndexed { i, s ->
            val f = s.toFloat() / Short.MAX_VALUE
            upsampled[i * 3] = f
            upsampled[i * 3 + 1] = f
            upsampled[i * 3 + 2] = f
        }

        // Process in RNNOISE_FRAME_SIZE chunks
        val outputUp = FloatArray(upsampled.size)
        var offset = 0
        while (offset + RNNOISE_FRAME_SIZE <= upsampled.size) {
            val chunk = upsampled.sliceArray(offset until offset + RNNOISE_FRAME_SIZE)
            val processed = nativeProcess(nativeHandle, chunk)
            processed.copyInto(outputUp, offset)
            offset += RNNOISE_FRAME_SIZE
        }

        // Downsample 48kHz → 16kHz (take every 3rd sample)
        return ShortArray(frame.size) { i ->
            (outputUp[i * 3] * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Software fallback: exponential noise floor estimation + spectral subtraction.
     */
    private fun processSoftware(frame: ShortArray): ShortArray {
        val floatFrame = FloatArray(frame.size) { frame[it].toFloat() / Short.MAX_VALUE }

        if (!noiseFloorInit) {
            floatFrame.forEachIndexed { i, v -> noiseFloor[i.coerceAtMost(noiseFloor.size - 1)] = Math.abs(v) }
            noiseFloorInit = true
        }

        // Update noise floor with slow tracking
        for (i in floatFrame.indices.take(noiseFloor.size)) {
            noiseFloor[i] = 0.98f * noiseFloor[i] + 0.02f * Math.abs(floatFrame[i])
        }

        // Subtract noise floor
        val output = FloatArray(frame.size) { i ->
            val n = if (i < noiseFloor.size) noiseFloor[i] else 0f
            val sign = if (floatFrame[i] >= 0) 1f else -1f
            sign * maxOf(0f, Math.abs(floatFrame[i]) - n * 1.5f)
        }

        return ShortArray(frame.size) { i ->
            (output[i] * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun release() {
        if (useNative && nativeHandle != 0L) {
            try { nativeDestroy(nativeHandle) } catch (_: Exception) {}
            nativeHandle = 0L
        }
    }
}
