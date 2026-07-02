package com.aladdin.assistant.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 2 – Custom Wake Word Engine ("Aladdin")
 *
 * Two-stage pipeline:
 *  Stage 1 – Energy + confidence gate (drops ~99% of silent frames, no inference)
 *  Stage 2 – TFLite / ONNX neural model inference on 1-second windows
 *
 * Requires CONSECUTIVE_DETECTIONS hits before firing to suppress false positives.
 * Stub heuristic active when model file is absent.
 */
class WakeWordEngine(private val context: Context) {
    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SIZE = 512
        private const val WINDOW_SAMPLES = SAMPLE_RATE
        private const val DETECTION_THRESHOLD = 0.80f
        private const val CONSECUTIVE_DETECTIONS = 2
        private const val ENERGY_GATE_RMS = 0.008f
        private const val MODEL_ASSET = "wake_word_aladdin.tflite"
        private var nativeAvailable = false
        init {
            nativeAvailable = try {
                System.loadLibrary("wakeword"); Log.d(TAG, "Wake word native lib loaded"); true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Wake word native lib not found – heuristic stub active"); false
            }
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeInfer(ctxPtr: Long, pcm: FloatArray): Float
    private external fun nativeFree(ctxPtr: Long)

    sealed class WakeWordEvent {
        object Detected : WakeWordEvent()
        data class ScoreUpdate(val score: Float) : WakeWordEvent()
    }

    private var nativeCtxPtr = 0L
    private val isListening = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null

    private val _flow = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 8)
    val wakeWordFlow: SharedFlow<WakeWordEvent> = _flow.asSharedFlow()

    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        if (nativeAvailable) {
            try {
                val path = ensureModel()
                nativeCtxPtr = nativeInit(path)
                Log.d(TAG, "Wake word model loaded")
                nativeCtxPtr != 0L
            } catch (e: Exception) { Log.e(TAG, "Model init error: ${e.message}", e); false }
        } else true
    }

    fun startListening() {
        if (isListening.getAndSet(true)) return
        listenJob = scope.launch { listenLoop() }
        Log.d(TAG, "Wake word listening started")
    }

    fun stopListening() {
        isListening.set(false); listenJob?.cancel(); listenJob = null
        Log.d(TAG, "Wake word listening stopped")
    }

    fun release() {
        stopListening()
        if (nativeCtxPtr != 0L) { try { nativeFree(nativeCtxPtr) } catch (_: Exception) {}; nativeCtxPtr = 0L }
        scope.cancel()
    }

    private suspend fun listenLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_SIZE * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { isListening.set(false); return }
        rec.startRecording()
        val ring = FloatArray(WINDOW_SAMPLES); var writePos = 0
        val frameBuf = ShortArray(FRAME_SIZE); var consecutive = 0

        try {
            while (isListening.get() && isActive) {
                val read = rec.read(frameBuf, 0, FRAME_SIZE)
                if (read <= 0) continue
                val frame = FloatArray(read) { frameBuf[it] / 32768f }
                val rms = sqrt(frame.sumOf { it.toDouble() * it } / frame.size).toFloat()
                if (rms < ENERGY_GATE_RMS) { consecutive = 0; continue }
                for (s in frame) { ring[writePos % WINDOW_SAMPLES] = s; writePos++ }
                if (writePos >= WINDOW_SAMPLES && writePos % (FRAME_SIZE * 4) == 0) {
                    val window = buildWindow(ring, writePos)
                    val score = infer(window)
                    _flow.tryEmit(WakeWordEvent.ScoreUpdate(score))
                    if (score >= DETECTION_THRESHOLD) {
                        consecutive++
                        if (consecutive >= CONSECUTIVE_DETECTIONS) {
                            Log.d(TAG, "WAKE WORD DETECTED! score=${"%.3f".format(score)}")
                            _flow.emit(WakeWordEvent.Detected)
                            consecutive = 0; writePos = 0
                        }
                    } else { if (consecutive > 0) consecutive-- }
                }
            }
        } finally { rec.stop(); rec.release(); isListening.set(false) }
    }

    private fun buildWindow(ring: FloatArray, pos: Int): FloatArray {
        val w = FloatArray(WINDOW_SAMPLES); val start = pos % WINDOW_SAMPLES
        for (i in 0 until WINDOW_SAMPLES) w[i] = ring[(start + i) % WINDOW_SAMPLES]
        return w
    }

    private fun infer(w: FloatArray): Float {
        if (nativeAvailable && nativeCtxPtr != 0L) {
            return try { nativeInfer(nativeCtxPtr, w) } catch (_: Exception) { heuristic(w) }
        }
        return heuristic(w)
    }

    private fun heuristic(w: FloatArray): Float {
        val cs = w.size / 5
        val e = (0 until 5).map { i -> w.slice(i * cs until (i + 1) * cs).sumOf { abs(it.toDouble()) } / cs }
        val peaks = e[0] > 0.02 && e[2] > 0.02 && e[4] > 0.02
        val dips   = e[1] < e[0] && e[3] < e[2]
        return if (peaks && dips) 0.35f else 0.05f
    }

    private fun ensureModel(): String {
        val dir = File(context.filesDir, "models/wake"); dir.mkdirs()
        val f = File(dir, MODEL_ASSET)
        if (!f.exists()) { try { context.assets.open(MODEL_ASSET).use { i -> f.outputStream().use { i.copyTo(it) } } } catch (_: Exception) {} }
        return f.absolutePath
    }
}
