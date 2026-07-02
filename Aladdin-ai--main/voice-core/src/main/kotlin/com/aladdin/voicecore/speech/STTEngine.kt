package com.aladdin.voicecore.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aladdin.voicecore.models.ErrorCode
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// ─── Phase 2: Streaming STT + Whisper.cpp Android Integration ────────────────
//
// Changes from Phase 1:
//  • Emits partial transcripts every PARTIAL_EMIT_INTERVAL_MS (300 ms) while recording
//  • Whisper.cpp JNI replaces Vosk for offline recognition (lower WER, faster)
//  • Silence detection reduced: 3 000 ms → 350 ms (via config.silenceTimeoutMs)
//  • Rolling audio buffer feeds Whisper for low-latency partial results
//  • Full-accuracy final decode fires after utterance ends

class STTEngine(
    private val context: Context,
    private val config: VoiceCoreConfig,
    private val audioFrames: ReceiveChannel<ShortArray>
) {
    companion object {
        private const val TAG = "STTEngine"
        private const val SAMPLE_RATE = 16_000
        // How often to emit a partial transcript while user is speaking
        private const val PARTIAL_EMIT_INTERVAL_MS = 300L
        // Rolling audio kept for Whisper context (max 30 s)
        private const val MAX_BUFFER_SECONDS = 30
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceCoreEvent> = _events

    // ── Whisper.cpp JNI ───────────────────────────────────────────────────────

    private var whisperCtxPtr = 0L
    private var whisperAvailable = false

    private external fun whisperInit(modelPath: String): Long
    private external fun whisperTranscribePartial(ctxPtr: Long, pcm: FloatArray, nSamples: Int): String
    private external fun whisperTranscribeFull(ctxPtr: Long, pcm: FloatArray, nSamples: Int): String
    private external fun whisperFree(ctxPtr: Long)

    init {
        whisperAvailable = try {
            System.loadLibrary("whisper")
            Log.i(TAG, "whisper.cpp native library loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "whisper.cpp not found – using stub: ${e.message}")
            false
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var isListening = false
    private val rollingBuffer = ArrayDeque<ShortArray>()
    private var rollingBufferSamples = 0
    private val maxRollingBufferSamples = SAMPLE_RATE * MAX_BUFFER_SECONDS
    private var lastPartialEmitMs = 0L
    private var lastSpeechMs = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun init() {
        if (whisperAvailable) {
            try {
                val modelPath = ensureWhisperModel()
                whisperCtxPtr = whisperInit(modelPath)
                Log.i(TAG, "Whisper.cpp initialised, model=$modelPath")
            } catch (e: Exception) {
                Log.e(TAG, "Whisper init error: ${e.message}", e)
                scope.launch {
                    _events.emit(VoiceCoreEvent.Error(
                        ErrorCode.STT_MODEL_NOT_FOUND,
                        "Whisper model error: ${e.message}", e
                    ))
                }
            }
        }
    }

    /** Begin streaming recognition. Emits partial transcripts as user speaks. */
    fun startListening() {
        if (isListening) return
        isListening = true
        rollingBuffer.clear()
        rollingBufferSamples = 0
        lastSpeechMs = System.currentTimeMillis()
        lastPartialEmitMs = System.currentTimeMillis()
        Log.i(TAG, "STT: streaming started")
        scope.launch { streamingLoop() }
    }

    /** Stop and emit final high-accuracy transcript. */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        Log.i(TAG, "STT: stop requested")
    }

    fun shutdown() {
        isListening = false
        scope.cancel()
        if (whisperCtxPtr != 0L) {
            try { whisperFree(whisperCtxPtr) } catch (_: Exception) {}
            whisperCtxPtr = 0L
        }
        Log.i(TAG, "STT shutdown")
    }

    // ── Streaming recognition loop ────────────────────────────────────────────

    private suspend fun streamingLoop() {
        _events.emit(VoiceCoreEvent.SpeechStarted())
        var lastPartialText = ""

        while (isListening && scope.isActive) {
            val frame = audioFrames.tryReceive().getOrNull()
            val now = System.currentTimeMillis()

            if (frame == null) {
                // Phase 2: silence timeout is 350 ms (was 3 000 ms)
                val silenceMs = now - lastSpeechMs
                if (silenceMs >= config.silenceTimeoutMs) {
                    Log.i(TAG, "Silence for ${silenceMs}ms – ending utterance")
                    break
                }
                delay(8)
                continue
            }

            lastSpeechMs = now
            appendRolling(frame)

            // Emit partial transcript every PARTIAL_EMIT_INTERVAL_MS
            if (now - lastPartialEmitMs >= PARTIAL_EMIT_INTERVAL_MS) {
                lastPartialEmitMs = now
                val pcm = buildPcmSnapshot()
                val partial = transcribePartial(pcm)
                if (partial.isNotBlank() && partial != lastPartialText) {
                    lastPartialText = partial
                    _events.emit(VoiceCoreEvent.Transcript(partial, isFinal = false))
                }
            }
        }

        // Final high-accuracy decode
        val finalPcm = buildPcmSnapshot()
        val finalText = transcribeFull(finalPcm)
        if (finalText.isNotBlank()) {
            Log.i(TAG, "Final transcript: $finalText")
            _events.emit(VoiceCoreEvent.Transcript(finalText, isFinal = true))
        }
        _events.emit(VoiceCoreEvent.SpeechEnded())
        isListening = false
    }

    // ── Whisper inference ─────────────────────────────────────────────────────

    private suspend fun transcribePartial(pcm: FloatArray): String = withContext(Dispatchers.Default) {
        if (!whisperAvailable || whisperCtxPtr == 0L) return@withContext stubPartial(pcm)
        try { whisperTranscribePartial(whisperCtxPtr, pcm, pcm.size) }
        catch (e: Exception) { Log.w(TAG, "partial error: ${e.message}"); "" }
    }

    private suspend fun transcribeFull(pcm: FloatArray): String = withContext(Dispatchers.Default) {
        if (!whisperAvailable || whisperCtxPtr == 0L) return@withContext stubFull(pcm)
        try { whisperTranscribeFull(whisperCtxPtr, pcm, pcm.size) }
        catch (e: Exception) { Log.w(TAG, "full error: ${e.message}"); "" }
    }

    // ── Rolling buffer ────────────────────────────────────────────────────────

    private fun appendRolling(frame: ShortArray) {
        rollingBuffer.addLast(frame)
        rollingBufferSamples += frame.size
        while (rollingBufferSamples > maxRollingBufferSamples && rollingBuffer.isNotEmpty()) {
            rollingBufferSamples -= rollingBuffer.removeFirst().size
        }
    }

    private fun buildPcmSnapshot(): FloatArray {
        val out = FloatArray(rollingBufferSamples)
        var idx = 0
        for (chunk in rollingBuffer) {
            for (s in chunk) {
                if (idx >= out.size) break
                out[idx++] = s / 32768.0f
            }
        }
        return out
    }

    // ── Stubs (no native lib) ─────────────────────────────────────────────────

    private fun stubPartial(pcm: FloatArray): String {
        val energy = pcm.take(800).sumOf { it.toDouble() * it }
        return if (energy > 0.001) "[listening…]" else ""
    }

    private fun stubFull(pcm: FloatArray) =
        if (pcm.isNotEmpty()) "[whisper.cpp not linked – transcript unavailable]" else ""

    // ── Model asset copy ──────────────────────────────────────────────────────

    private fun ensureWhisperModel(): String {
        val dir = File(context.filesDir, "models/whisper")
        dir.mkdirs()
        val f = File(dir, "ggml-small.en.bin")
        if (!f.exists()) {
            try {
                context.assets.open("ggml-small.en.bin").use { i ->
                    f.outputStream().use { i.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy whisper model asset: ${e.message}")
            }
        }
        return f.absolutePath
    }
}
