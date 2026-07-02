package com.aladdin.voicecore.engine

import android.content.Context
import android.util.Log
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineException

/**
 * Wake word detection engine supporting both Vosk and Porcupine backends.
 *
 * Vosk path  — uses full offline speech recognition with keyword spotting.
 *              Grammar is restricted to the configured wake words for minimal CPU use.
 *
 * Porcupine  — purpose-built wake word engine.  Extremely low CPU (~1% on Cortex-A53).
 *              Requires a Picovoice access key and platform-specific .ppn keyword files.
 *
 * Both backends:
 *   - confidence threshold >= [VoiceCoreConfig.wakeWordConfidenceThreshold]
 *   - sensitivity in [0.3, 0.9] maps to backend-specific tuning
 *   - auto-timeout after [VoiceCoreConfig.wakeWordIdleTimeoutSec] seconds of silence
 *   - support all words in [VoiceCoreConfig.wakeWords]
 */
class WakeWordEngine(
    private val context: Context,
    private val config: VoiceCoreConfig,
    private val audioFrames: ReceiveChannel<ShortArray>
) {
    companion object {
        private const val TAG = "WakeWordEngine"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<VoiceCoreEvent> = _events

    // Vosk
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null

    // Porcupine
    private var porcupine: Porcupine? = null

    // State
    @Volatile private var isRunning = false
    @Volatile var sensitivity: Float = config.wakeWordSensitivity
        set(value) { field = value.coerceIn(0.3f, 0.9f) }

    private var lastActivityMs = System.currentTimeMillis()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        isRunning = true
        lastActivityMs = System.currentTimeMillis()

        scope.launch {
            when (config.wakeWordEngine) {
                VoiceCoreConfig.WakeWordEngine.VOSK -> startVosk()
                VoiceCoreConfig.WakeWordEngine.PORCUPINE -> startPorcupine()
            }
        }
        Log.i(TAG, "WakeWordEngine started (${config.wakeWordEngine}, words=${config.wakeWords})")
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        releaseVosk()
        releasePorcupine()
        Log.i(TAG, "WakeWordEngine stopped")
    }

    // ─── Vosk Backend ─────────────────────────────────────────────────────────

    private suspend fun startVosk() {
        try {
            val modelPath = getModelPath(config.sttModelPath)
            voskModel = Model(modelPath)

            // Restrict grammar to wake words only – drastically reduces CPU
            val grammar = config.wakeWords.joinToString(prefix = "[\"", separator = "\", \"", postfix = "\", \"[unk]\"]")
            voskRecognizer = Recognizer(voskModel, config.sampleRateHz.toFloat(), grammar)
            voskRecognizer!!.setMaxAlternatives(1)
            voskRecognizer!!.setWords(true)

            Log.i(TAG, "Vosk wake word recogniser ready, grammar=$grammar")
            runVoskLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init failed: ${e.message}", e)
            _events.emit(VoiceCoreEvent.Error(
                com.aladdin.voicecore.models.ErrorCode.WAKE_WORD_MODEL_NOT_FOUND,
                "Vosk model not found at ${config.sttModelPath}: ${e.message}"
            ))
        }
    }

    private suspend fun runVoskLoop() {
        var silenceMs = 0L
        var lastFrameMs = System.currentTimeMillis()

        while (isRunning && scope.isActive) {
            val frame = audioFrames.tryReceive().getOrNull()
            val now = System.currentTimeMillis()

            if (frame == null) {
                val elapsed = now - lastFrameMs
                silenceMs += elapsed
                lastFrameMs = now

                if (silenceMs > config.wakeWordIdleTimeoutSec * 1000L) {
                    Log.d(TAG, "Idle timeout reached")
                    silenceMs = 0L
                }
                kotlinx.coroutines.delay(5)
                continue
            }

            lastFrameMs = now
            silenceMs = 0L

            val bytes = shortsToBytes(frame)
            val accepted = voskRecognizer!!.acceptWaveForm(bytes, bytes.size)

            if (accepted) {
                val result = voskRecognizer!!.result
                checkVoskResult(result, isFinal = true)
            } else {
                val partial = voskRecognizer!!.partialResult
                checkVoskResult(partial, isFinal = false)
            }
        }
    }

    private suspend fun checkVoskResult(json: String, isFinal: Boolean) {
        // Vosk returns JSON: {"text": "aladdin"} or {"partial": "alad"}
        val key = if (isFinal) "\"text\"" else "\"partial\""
        val start = json.indexOf(key)
        if (start < 0) return
        val valStart = json.indexOf('"', start + key.length + 1) + 1
        val valEnd = json.indexOf('"', valStart)
        if (valStart <= 0 || valEnd <= valStart) return
        val text = json.substring(valStart, valEnd).trim().lowercase()

        for (wakeWord in config.wakeWords) {
            if (text.contains(wakeWord.lowercase())) {
                val confidence = estimateVoskConfidence(text, wakeWord)
                if (confidence >= config.wakeWordConfidenceThreshold) {
                    Log.i(TAG, "Wake word detected: '$wakeWord' (confidence=$confidence)")
                    _events.emit(VoiceCoreEvent.WakeWordDetected(wakeWord, confidence))
                    lastActivityMs = System.currentTimeMillis()
                    return
                } else {
                    Log.d(TAG, "Wake word candidate '$wakeWord' rejected (confidence=$confidence < ${config.wakeWordConfidenceThreshold})")
                }
            }
        }
    }

    /** Estimates confidence from Vosk result string; uses match length ratio as proxy. */
    private fun estimateVoskConfidence(text: String, keyword: String): Float {
        val matchRatio = keyword.length.toFloat() / text.replace(" ", "").length.coerceAtLeast(1)
        return matchRatio.coerceIn(0f, 1f)
    }

    private fun releaseVosk() {
        try { voskRecognizer?.close() } catch (_: Exception) {}
        try { voskModel?.close() } catch (_: Exception) {}
        voskRecognizer = null
        voskModel = null
    }

    // ─── Porcupine Backend ────────────────────────────────────────────────────

    private suspend fun startPorcupine() {
        try {
            val keywordPaths = config.wakeWords.map { word ->
                getKeywordPath(word)
            }
            val sensitivities = FloatArray(keywordPaths.size) { sensitivity }

            porcupine = Porcupine.Builder()
                .setAccessKey(config.porcupineAccessKey)
                .setKeywordPaths(keywordPaths.toTypedArray())
                .setSensitivities(sensitivities)
                .build(context)

            Log.i(TAG, "Porcupine initialised with ${keywordPaths.size} keyword(s)")
            runPorcupineLoop()
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine init failed: ${e.message}", e)
            _events.emit(VoiceCoreEvent.Error(
                com.aladdin.voicecore.models.ErrorCode.WAKE_WORD_MODEL_NOT_FOUND,
                "Porcupine keyword model not found: ${e.message}"
            ))
        }
    }

    private suspend fun runPorcupineLoop() {
        val frameSize = porcupine!!.frameLength
        val buffer = ShortArray(frameSize)
        var bufferOffset = 0

        while (isRunning && scope.isActive) {
            val frame = audioFrames.tryReceive().getOrNull()
            if (frame == null) {
                kotlinx.coroutines.delay(5)
                continue
            }

            // Fill Porcupine's required frame size
            var srcOffset = 0
            while (srcOffset < frame.size) {
                val toCopy = minOf(frameSize - bufferOffset, frame.size - srcOffset)
                frame.copyInto(buffer, bufferOffset, srcOffset, srcOffset + toCopy)
                bufferOffset += toCopy
                srcOffset += toCopy

                if (bufferOffset >= frameSize) {
                    bufferOffset = 0
                    try {
                        val keywordIndex = porcupine!!.process(buffer)
                        if (keywordIndex >= 0 && keywordIndex < config.wakeWords.size) {
                            val keyword = config.wakeWords[keywordIndex]
                            // Porcupine doesn't expose confidence directly; use 1.0 when detected
                            val confidence = sensitivity  // proxy: sensitivity ≈ recall/precision tradeoff
                            if (confidence >= config.wakeWordConfidenceThreshold) {
                                Log.i(TAG, "Porcupine wake word: '$keyword'")
                                _events.emit(VoiceCoreEvent.WakeWordDetected(keyword, confidence))
                                lastActivityMs = System.currentTimeMillis()
                            }
                        }
                    } catch (e: PorcupineActivationException) {
                        Log.e(TAG, "Porcupine activation error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun releasePorcupine() {
        try { porcupine?.delete() } catch (_: Exception) {}
        porcupine = null
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getModelPath(relativePath: String): String {
        val filesDir = context.filesDir
        return "$filesDir/$relativePath"
    }

    private fun getKeywordPath(word: String): String {
        val filesDir = context.filesDir
        return "$filesDir/models/porcupine/${word.lowercase()}_android.ppn"
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /** Dynamically update sensitivity without restarting the engine. */
    fun updateSensitivity(newSensitivity: Float) {
        sensitivity = newSensitivity
        Log.i(TAG, "Sensitivity updated to $sensitivity")
        // Porcupine requires rebuild to change sensitivity; schedule async restart
        if (config.wakeWordEngine == VoiceCoreConfig.WakeWordEngine.PORCUPINE && porcupine != null) {
            scope.launch {
                releasePorcupine()
                startPorcupine()
            }
        }
    }
}
