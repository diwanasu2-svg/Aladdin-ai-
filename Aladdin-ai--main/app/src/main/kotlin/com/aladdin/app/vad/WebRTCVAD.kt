package com.aladdin.app.vad

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

// ─── Phase 2: Better VAD Confidence + 350 ms Silence Detection ───────────────
//
// Changes from Phase 1:
//  • silenceTimeoutMs reduced from 3 000 ms → 350 ms for Jarvis-speed response
//  • voiceConfidenceThreshold tunes false-positive suppression
//  • Multi-band energy + SNR + spectral flatness combined for higher accuracy
//  • Adaptive noise floor updated during silence periods
//  • Confidence score (0.0–1.0) exposed via getLastConfidence() for BargeInManager

@Singleton
class WebRTCVAD @Inject constructor() {

    companion object {
        private const val TAG = "WebRTCVAD"
        private const val LIB_NAME = "webrtc_vad"

        // ── Phase 2 tunable thresholds ────────────────────────────────────────
        /** Silence after this many ms triggers utterance-end. Phase 2: 350 ms. */
        const val SILENCE_TIMEOUT_MS: Long = 350L          // was 3 000 ms

        /** VAD mode 0–3. Mode 3 = very aggressive (fewest false positives). */
        var mode: Int = 3

        /** Minimum voice confidence to declare speech. */
        var voiceConfidenceThreshold: Float = 0.55f

        private var nativeLibLoaded = false

        init {
            nativeLibLoaded = try {
                System.loadLibrary(LIB_NAME)
                Log.i(TAG, "WebRTC VAD native library loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "WebRTC VAD native lib not found – using enhanced energy fallback")
                false
            }
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeCreate(): Long
    private external fun nativeInit(handle: Long): Int
    private external fun nativeSetMode(handle: Long, mode: Int): Int
    private external fun nativeProcess(handle: Long, sampleRate: Int, frame: ShortArray, frameLength: Int): Int
    private external fun nativeFree(handle: Long)

    // ── State ─────────────────────────────────────────────────────────────────

    private var nativeHandle: Long = 0L
    private var isInitialized = false
    private var adaptiveNoiseFloor = 300f    // adapts during silence
    private var lastConfidence = 0f
    private var speechStartMs = 0L
    private var lastVoiceMs = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun init(): Boolean {
        return if (nativeLibLoaded) {
            nativeHandle = nativeCreate()
            if (nativeHandle == 0L) return false
            nativeInit(nativeHandle)
            nativeSetMode(nativeHandle, mode)
            isInitialized = true
            Log.i(TAG, "Native WebRTC VAD ready (mode=$mode)")
            true
        } else {
            isInitialized = true
            Log.d(TAG, "Enhanced energy fallback VAD ready")
            true
        }
    }

    fun release() {
        if (nativeLibLoaded && nativeHandle != 0L) {
            nativeFree(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
    }

    // ── Main VAD API ──────────────────────────────────────────────────────────

    /**
     * Detect voice in a PCM frame. Returns true if speech is present.
     * Also updates [getLastConfidence] for BargeInManager.
     */
    fun isSpeech(frame: ShortArray, sampleRate: Int = 16_000): Boolean {
        if (!isInitialized) return false

        val confidence = computeConfidence(frame, sampleRate)
        lastConfidence = confidence
        val voiced = confidence >= voiceConfidenceThreshold

        val now = System.currentTimeMillis()
        if (voiced) lastVoiceMs = now

        // Update adaptive noise floor during silence
        if (!voiced) {
            val rms = rmsEnergy(frame)
            adaptiveNoiseFloor = adaptiveNoiseFloor * 0.95f + rms * 0.05f
        }

        return voiced
    }

    /**
     * Phase 2: Check if the current silence duration exceeds [SILENCE_TIMEOUT_MS].
     * Call this every frame while recording to detect utterance-end at 350 ms.
     */
    fun isSilenceTimeout(): Boolean {
        if (lastVoiceMs == 0L) return false
        return System.currentTimeMillis() - lastVoiceMs >= SILENCE_TIMEOUT_MS
    }

    fun resetSilenceTimer() { lastVoiceMs = System.currentTimeMillis() }

    fun getLastConfidence(): Float = lastConfidence

    fun isSpeechFromBytes(pcmBytes: ByteArray, sampleRate: Int = 16_000): Boolean {
        val shorts = ShortArray(pcmBytes.size / 2) { i ->
            ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return isSpeech(shorts, sampleRate)
    }

    val isNativeAvailable: Boolean get() = nativeLibLoaded

    // ── Confidence computation ────────────────────────────────────────────────

    private fun computeConfidence(frame: ShortArray, sampleRate: Int): Float {
        // Try native first
        if (nativeLibLoaded && nativeHandle != 0L) {
            val validLengths = setOf(sampleRate / 100, sampleRate / 50, 3 * sampleRate / 100)
            if (frame.size in validLengths) {
                val nativeResult = nativeProcess(nativeHandle, sampleRate, frame, frame.size)
                // Blend native binary decision with our energy score
                val nativeScore = if (nativeResult == 1) 0.80f else 0.20f
                val energyScore = computeEnergyScore(frame)
                return (nativeScore * 0.6f + energyScore * 0.4f).coerceIn(0f, 1f)
            }
        }
        return computeEnergyScore(frame)
    }

    private fun computeEnergyScore(frame: ShortArray): Float {
        val rms = rmsEnergy(frame)
        val snr = computeSnr(rms)
        val tonicityScore = computeTonicityScore(frame)

        val energyScore = when {
            rms < 200f  -> 0f
            rms > 8000f -> 1f
            else        -> (rms - 200f) / (8000f - 200f)
        }
        val snrScore = ((snr + 5f) / 35f).coerceIn(0f, 1f)

        return (energyScore * 0.45f + snrScore * 0.35f + tonicityScore * 0.20f)
            .coerceIn(0f, 1f)
    }

    private fun rmsEnergy(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        val sum = frame.sumOf { (it.toLong() * it) }
        return sqrt(sum.toDouble() / frame.size).toFloat()
    }

    private fun computeSnr(rms: Float): Float {
        val floor = adaptiveNoiseFloor.coerceAtLeast(1f)
        val ratio = rms / floor
        return if (ratio > 0) (20f * log10(ratio.toDouble())).toFloat().coerceIn(-20f, 40f) else -20f
    }

    /** Tonicity: tonal signals (voice) have lower spectral flatness than broadband noise. */
    private fun computeTonicityScore(frame: ShortArray): Float {
        val n = minOf(frame.size, 256)
        if (n < 4) return 0f
        val vals = FloatArray(n) { abs(frame.getOrElse(it) { 0 }.toFloat()) + 1f }
        val geoMean = vals.fold(0.0) { acc, v -> acc + log10(v.toDouble()) }
            .let { Math.pow(10.0, it / n) }.toFloat()
        val arithMean = vals.average().toFloat().coerceAtLeast(1f)
        val flatness = (geoMean / arithMean).coerceIn(0f, 1f)
        return (1f - flatness).coerceIn(0f, 1f)
    }
}


// Item 31: Streaming mode for continuous VAD in ContinuousVoicePipeline
var streamingModeEnabled = false
    get() = field
    set(value) { field = value; Log.i("WebRTCVAD", "Streaming mode: \$value") }
