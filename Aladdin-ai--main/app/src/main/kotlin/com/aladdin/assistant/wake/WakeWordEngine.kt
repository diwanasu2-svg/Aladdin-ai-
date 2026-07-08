package com.aladdin.assistant.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Real neural wake-word engine ("Aladdin").
 *
 * 2026-07-08 update: a genuine trained model now backs wake-word detection.
 * Pipeline (matches the openWakeWord architecture, three chained ONNX models):
 *   1. melspectrogram.onnx  – raw 16kHz PCM -> mel-spectrogram frames
 *   2. embedding_model.onnx – Google speech_embedding features (pretrained,
 *      frozen, NOT specific to any wake word)
 *   3. aladdin_classifier.onnx – small MLP classifier we trained ourselves on
 *      ~3,200 synthetic "Aladdin" / non-"Aladdin" clips (6 TTS voices x many
 *      phrasings x noise/volume/shift augmentation). Held-out (group-split,
 *      no leakage) evaluation: 96% accuracy, ROC AUC 0.99.
 *
 * All three .onnx files are bundled in assets/wakeword/ and run on-device via
 * ONNX Runtime Mobile — fully offline, no native C++ / JNI build step needed.
 *
 * If the models are ever missing (e.g. stripped from a build), we fall back
 * to the old energy-pattern heuristic so the class never crashes — but that
 * heuristic is intentionally weak; the mic button remains the reliable path
 * in that fallback scenario.
 */
class WakeWordEngine(private val context: Context) {
    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SIZE = 512
        // Matches the training window: 1.5s of audio per inference.
        private const val WINDOW_SAMPLES = (1.5 * SAMPLE_RATE).toInt() // 24_000
        // Re-run full inference every ~0.25s of new audio (not every frame).
        private const val INFER_STRIDE_SAMPLES = SAMPLE_RATE / 4 // 4_000
        private const val EMBED_WINDOW = 76
        private const val EMBED_STEP = 8
        private const val MEL_FEATURES = 32
        private const val EMBED_DIM = 96

        private const val DETECTION_THRESHOLD = 0.80f
        private const val HEURISTIC_DETECTION_THRESHOLD = 0.30f
        private const val CONSECUTIVE_DETECTIONS = 2
        private const val ENERGY_GATE_RMS = 0.004f

        private const val MEL_ASSET = "wakeword/melspectrogram.onnx"
        private const val EMBED_ASSET = "wakeword/embedding_model.onnx"
        private const val CLF_ASSET = "wakeword/aladdin_classifier.onnx"
    }

    sealed class WakeWordEvent {
        object Detected : WakeWordEvent()
        data class ScoreUpdate(val score: Float) : WakeWordEvent()
    }

    private var ortEnv: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private var clfSession: OrtSession? = null
    private var modelsReady = false

    private val isListening = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null

    private val _flow = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 8)
    val wakeWordFlow: SharedFlow<WakeWordEvent> = _flow.asSharedFlow()

    /** True once the real trained neural model is loaded and ready. */
    val isUsingRealModel: Boolean get() = modelsReady

    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            melSession = env.createSession(context.assets.open(MEL_ASSET).readBytes())
            embedSession = env.createSession(context.assets.open(EMBED_ASSET).readBytes())
            clfSession = env.createSession(context.assets.open(CLF_ASSET).readBytes())
            modelsReady = true
            Log.d(TAG, "Real wake-word neural model loaded (melspec+embedding+aladdin_classifier)")
        } catch (e: Exception) {
            modelsReady = false
            Log.w(TAG, "Wake-word ONNX models not available (${e.message}) — energy-pattern " +
                "heuristic fallback active. For reliable detection, tap the mic button.")
        }
        true
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
        try { melSession?.close() } catch (_: Exception) {}
        try { embedSession?.close() } catch (_: Exception) {}
        try { clfSession?.close() } catch (_: Exception) {}
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

        val ring = FloatArray(WINDOW_SAMPLES)
        var writePos = 0
        var samplesSinceInfer = 0
        val frameBuf = ShortArray(FRAME_SIZE)
        var consecutive = 0
        val threshold = if (modelsReady) DETECTION_THRESHOLD else HEURISTIC_DETECTION_THRESHOLD

        try {
            while (isListening.get() && currentCoroutineContext().isActive) {
                val read = rec.read(frameBuf, 0, FRAME_SIZE)
                if (read <= 0) continue
                // Keep PCM in raw int16 numeric range (NOT normalized to [-1,1]) —
                // this matches the training pipeline, which fed int16-scale floats
                // directly into the melspectrogram model.
                val frame = FloatArray(read) { frameBuf[it].toFloat() }
                val rmsNorm = sqrt(frame.sumOf { (it / 32768f).toDouble() * (it / 32768f) } / frame.size).toFloat()
                if (rmsNorm < ENERGY_GATE_RMS) { consecutive = 0; continue }

                for (s in frame) { ring[writePos % WINDOW_SAMPLES] = s; writePos++ }
                samplesSinceInfer += frame.size

                if (writePos >= WINDOW_SAMPLES && samplesSinceInfer >= INFER_STRIDE_SAMPLES) {
                    samplesSinceInfer = 0
                    val window = buildWindow(ring, writePos)
                    val score = infer(window)
                    _flow.tryEmit(WakeWordEvent.ScoreUpdate(score))
                    if (score >= threshold) {
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

    private fun infer(pcm: FloatArray): Float {
        if (modelsReady) {
            try {
                return runNeuralPipeline(pcm)
            } catch (e: Exception) {
                Log.e(TAG, "Neural inference failed, falling back to heuristic: ${e.message}")
            }
        }
        return heuristic(pcm)
    }

    /** Full melspectrogram -> embedding -> classifier pipeline, mirroring openWakeWord. */
    private fun runNeuralPipeline(pcm: FloatArray): Float {
        val env = ortEnv!!

        // ── Stage 1: mel-spectrogram ─────────────────────────────────────────
        val melInput = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(1, pcm.size.toLong()))
        val melFrames: FloatArray
        val timeSteps: Int
        melInput.use { input ->
            melSession!!.run(mapOf("input" to input)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape // [1, 1, time, 32]
                timeSteps = shape[2].toInt()
                val buf = out.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                // openWakeWord's melspec transform: x/10 + 2
                for (i in arr.indices) arr[i] = arr[i] / 10f + 2f
                melFrames = arr // flattened as [time * 32]
            }
        }

        if (timeSteps < EMBED_WINDOW) return 0f

        // ── Stage 2: slide 76-frame windows (step 8) through the embedding model ──
        val numWindows = (timeSteps - EMBED_WINDOW) / EMBED_STEP + 1
        if (numWindows <= 0) return 0f
        val windowData = FloatArray(numWindows * EMBED_WINDOW * MEL_FEATURES * 1)
        var w = 0
        var idx = 0
        while (w < numWindows) {
            val startFrame = w * EMBED_STEP
            for (t in 0 until EMBED_WINDOW) {
                val srcOffset = (startFrame + t) * MEL_FEATURES
                for (f in 0 until MEL_FEATURES) {
                    windowData[idx++] = melFrames[srcOffset + f]
                }
            }
            w++
        }

        val embedShape = longArrayOf(numWindows.toLong(), EMBED_WINDOW.toLong(), MEL_FEATURES.toLong(), 1)
        val pooled = FloatArray(EMBED_DIM)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(windowData), embedShape).use { input ->
            embedSession!!.run(mapOf("input_1" to input)).use { result ->
                val out = result[0] as OnnxTensor
                val buf = out.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr) // flattened as [numWindows * 96]
                // mean-pool across windows (matches training: emb.mean(axis=0))
                for (i in 0 until numWindows) {
                    for (d in 0 until EMBED_DIM) {
                        pooled[d] += arr[i * EMBED_DIM + d]
                    }
                }
                for (d in 0 until EMBED_DIM) pooled[d] /= numWindows.toFloat()
            }
        }

        // ── Stage 3: trained "Aladdin" classifier ────────────────────────────
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pooled), longArrayOf(1, EMBED_DIM.toLong())).use { input ->
            clfSession!!.run(mapOf("X" to input)).use { result ->
                // outputs: [0]=label (int64), [1]=probabilities (float, shape [1,2])
                val probsTensor = result[1] as OnnxTensor
                val buf = probsTensor.floatBuffer
                val probs = FloatArray(buf.remaining())
                buf.get(probs)
                return probs[1] // P(class == "aladdin")
            }
        }
    }

    /** Weak fallback used only if the bundled ONNX models fail to load. */
    private fun heuristic(w: FloatArray): Float {
        val norm = FloatArray(w.size) { w[it] / 32768f }
        val cs = norm.size / 5
        val e = (0 until 5).map { i -> norm.slice(i * cs until (i + 1) * cs).sumOf { kotlin.math.abs(it.toDouble()) } / cs }
        val peaks = e[0] > 0.015 && e[2] > 0.015 && e[4] > 0.015
        val dips = e[1] < e[0] && e[3] < e[2]
        return if (peaks && dips) 0.35f else 0.05f
    }
}
