package com.aladdin.app.wakeword

import android.content.Context
import android.util.Log
import com.aladdin.app.audio.BackgroundMicCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val TAG = "WakeWordDetector"
private const val MODEL_SUBDIR = "models/wakeword"
private const val MODEL_FILENAME = "aladdin_wakeword.tflite"

/**
 * WakeWordDetector — Items 11-15: Pure neural TFLite wake-word detection.
 *
 * Item 13: ALL heuristic/syllable code REMOVED. Detection is 100% neural.
 * Item 14: Real TFLite inference enabled (GPU delegate when available).
 * Item 15: Threshold tunable via WakeWordConfig.detectionThreshold (default 0.85).
 *
 * Pipeline: energy gate → TFLite score → sliding-window vote → consecutive-hit confirm
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val micCapture: BackgroundMicCapture,
    private val config: WakeWordConfig
) {
    companion object {
        private const val CONSECUTIVE_HIT_THRESHOLD = 3
        private const val ENERGY_GATE_RATIO = 1.5f
        private const val NOISE_FLOOR_ALPHA = 0.02f
    }

    private val _wakeEvents = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    val wakeEvents: SharedFlow<WakeEvent> = _wakeEvents

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val windowScores = FloatArray(config.votingWindowFrames) { 0f }
    private var windowIdx = 0
    private val lastDetectionMs = AtomicLong(0L)

    @Volatile var isListening = false
        private set

    private var consecutiveHits = 0
    private var consecutiveMisses = 0
    private var adaptiveNoiseFloor = config.energyThreshold
    private var tfliteInterpreter: Any? = null
    private var isModelLoaded = false

    fun startListening() {
        if (isListening) return
        isListening = true
        loadTFLiteModel()
        if (!isModelLoaded) Log.e(TAG, "TFLite model not loaded. Download via ModelDownloaderHelper.")
        micCapture.addListener(::onAudioFrame)
        if (!micCapture.isRunning) micCapture.start()
        Log.i(TAG, "Wake-word detection started (neural-only, threshold=\${config.detectionThreshold})")
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        micCapture.removeListener(::onAudioFrame)
        consecutiveHits = 0
        Log.i(TAG, "Wake-word detection stopped")
    }

    private fun onAudioFrame(frame: ShortArray) {
        val now = System.currentTimeMillis()
        val energy = computeRmsEnergy(frame)
        updateNoiseFloor(energy)
        // Item 13: NO heuristic — energy gate only for skipping silence, all detection is neural
        if (energy < adaptiveNoiseFloor * ENERGY_GATE_RATIO) {
            windowScores[windowIdx] = 0f
            windowIdx = (windowIdx + 1) % config.votingWindowFrames
            consecutiveMisses++
            if (consecutiveMisses > config.votingWindowFrames) consecutiveHits = 0
            return
        }
        consecutiveMisses = 0
        // Item 14: Real neural inference
        val score = runNeuralInference(frame)
        windowScores[windowIdx] = score
        windowIdx = (windowIdx + 1) % config.votingWindowFrames
        val avgScore = windowScores.average().toFloat()
        if (avgScore >= config.detectionThreshold) consecutiveHits++
        else if (consecutiveHits > 0) consecutiveHits--
        if (consecutiveHits >= CONSECUTIVE_HIT_THRESHOLD) {
            if (now - lastDetectionMs.get() > config.cooldownMs) {
                lastDetectionMs.set(now)
                consecutiveHits = 0
                windowScores.fill(0f)
                Log.i(TAG, "Wake word DETECTED: score=\$avgScore (neural)")
                scope.launch { _wakeEvents.emit(WakeEvent(config.keyword, avgScore, now)) }
            }
        }
    }

    /** Item 14: Real TFLite neural inference — no heuristic fallback. */
    private fun runNeuralInference(frame: ShortArray): Float {
        val interp = tfliteInterpreter ?: return 0f
        return try {
            val floatFrame = FloatArray(frame.size) { frame[it].toFloat() / 32768f }
            val inputBuf = ByteBuffer.allocateDirect(floatFrame.size * 4).apply {
                order(ByteOrder.nativeOrder())
                floatFrame.forEach { putFloat(it) }
                rewind()
            }
            val output = Array(1) { FloatArray(2) }
            interp.javaClass.getMethod("run", Any::class.java, Any::class.java)
                .invoke(interp, inputBuf, output)
            output[0][1]  // class 1 = wake word detected
        } catch (e: Exception) { Log.w(TAG, "TFLite error: \${e.message}"); 0f }
    }

    private fun computeRmsEnergy(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        return sqrt(frame.sumOf { it.toLong() * it }.toDouble() / frame.size).toFloat()
    }

    private fun updateNoiseFloor(energy: Float) {
        if (energy < adaptiveNoiseFloor * 2f)
            adaptiveNoiseFloor = adaptiveNoiseFloor * (1f - NOISE_FLOOR_ALPHA) + energy * NOISE_FLOOR_ALPHA
    }

    private fun loadTFLiteModel() {
        val modelFile = copyModelFromAssets() ?: return
        try {
            val interpClass = Class.forName("org.tensorflow.lite.Interpreter")
            val optClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
            val opts = optClass.newInstance()
            optClass.getMethod("setNumThreads", Int::class.java).invoke(opts, 2)
            // Task 35: Prefer GPU delegate → NNAPI delegate → CPU fallback
            var delegateAdded = false
            try {
                val gpu = Class.forName("org.tensorflow.lite.gpu.GpuDelegate").newInstance()
                optClass.getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate")).invoke(opts, gpu)
                Log.i(TAG, "GPU delegate enabled for TFLite")
                delegateAdded = true
            } catch (_: Exception) {
                Log.d(TAG, "GPU delegate unavailable — trying NNAPI")
            }
            if (!delegateAdded) {
                try {
                    // Task 35: NNAPI delegate — hardware accelerator (DSP/NPU) on supported devices
                    val nnApiDelegate = Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate").newInstance()
                    optClass.getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate")).invoke(opts, nnApiDelegate)
                    Log.i(TAG, "NNAPI delegate enabled for TFLite")
                    delegateAdded = true
                } catch (_: Exception) {
                    Log.d(TAG, "NNAPI delegate unavailable — using CPU threads")
                }
            }
            if (!delegateAdded) {
                // CPU fallback with 4 threads
                optClass.getMethod("setNumThreads", Int::class.java).invoke(opts, 4)
                Log.i(TAG, "Using CPU (4 threads) for TFLite")
            }
            tfliteInterpreter = interpClass.getConstructor(File::class.java, optClass).newInstance(modelFile, opts)
            isModelLoaded = true
            Log.i(TAG, "TFLite wake-word model loaded")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "TFLite runtime missing — add org.tensorflow:tensorflow-lite dependency")
        } catch (e: Exception) { Log.e(TAG, "TFLite load failed: \${e.message}") }
    }

    private fun copyModelFromAssets(): File? {
        val f = File(context.filesDir, "\$MODEL_SUBDIR/\$MODEL_FILENAME")
        if (f.exists()) return f
        return try {
            f.parentFile?.mkdirs()
            val assetPath = if (context.assets.list("models/wakeword")?.contains(MODEL_FILENAME) == true) "models/wakeword/$MODEL_FILENAME" else MODEL_FILENAME
            context.assets.open(assetPath).use { i -> f.outputStream().use { i.copyTo(it) } }
            f
        } catch (_: Exception) { null }
    }
}

data class WakeEvent(val keyword: String, val confidence: Float, val timestampMs: Long = System.currentTimeMillis())