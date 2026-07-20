package com.aladdin.assistant.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aladdin.assistant.noise.RNNoise
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 – Streaming STT
 * Captures microphone audio and emits partial + final transcripts in real-time.
 * Whisper.cpp is used for on-device inference via [WhisperEngine].
 * Silence detection fires at 350 ms (configurable via [silenceTimeoutMs]).
 *
 * E2E test fix (2026-07-08): [RNNoise] existed and was initialised by
 * JarvisOrchestrator but nothing ever called processFrame/processBuffer on
 * it — raw, un-denoised mic audio went straight to Whisper. Now optionally
 * accepted here and applied to every captured chunk before it's buffered or
 * transcribed, so noise suppression is actually part of the pipeline (falls
 * back to the built-in spectral-subtraction path when librnnoise.so isn't
 * bundled, same as before — this only changes *whether* it's called).
 */
class StreamingSTT(
    private val context: Context,
    private val whisperEngine: WhisperEngine,
    private val rnNoise: RNNoise? = null
) {
    companion object {
        private const val TAG = "StreamingSTT"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_MS = 300L
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
        private const val MAX_BUFFER_SEC = 30
        // Absolute max recording time — prevents the loop running forever when
        // Whisper returns blank partials (e.g. native lib not linked).
        private const val MAX_RECORD_MS = 15_000L
    }

    var silenceTimeoutMs: Long = 1500L  // increased: 350 ms was too aggressive

    sealed class TranscriptEvent {
        data class Partial(val text: String) : TranscriptEvent()
        data class Final(val text: String) : TranscriptEvent()
        object Error : TranscriptEvent()
    }

    private val isRecording = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val _flow = MutableSharedFlow<TranscriptEvent>(replay = 1, extraBufferCapacity = 64)
    val transcriptFlow: SharedFlow<TranscriptEvent> = _flow.asSharedFlow()

    private val rollingBuffer = ArrayDeque<ShortArray>()
    private var rollingBufferSamples = 0
    private val maxRollingBufferSamples = SAMPLE_RATE * MAX_BUFFER_SEC

    fun startStreaming() {
        if (isRecording.getAndSet(true)) return
        rollingBuffer.clear(); rollingBufferSamples = 0
        job = scope.launch { recordLoop() }
    }

    fun stopStreaming() {
        isRecording.set(false)
        job?.cancel(); job = null
    }

    private suspend fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            _flow.emit(TranscriptEvent.Error); isRecording.set(false); return
        }
        rec.startRecording()
        val buf = ShortArray(CHUNK_SAMPLES)
        var lastPartial = ""
        var lastVoiceMs = System.currentTimeMillis()
        val recordingStartMs = System.currentTimeMillis()

        try {
            while (isRecording.get() && currentCoroutineContext().isActive) {
                val read = rec.read(buf, 0, CHUNK_SAMPLES)
                if (read <= 0) { delay(5); continue }
                var chunk = buf.copyOf(read)
                if (rnNoise != null) chunk = denoise(chunk)
                val rms = rms(chunk)
                if (rms > 150f) lastVoiceMs = System.currentTimeMillis()

                append(chunk)

                val pcm = snapshot()
                val partial = whisperEngine.transcribePartial(pcm)
                if (partial.isNotBlank() && partial != lastPartial) {
                    lastPartial = partial
                    _flow.emit(TranscriptEvent.Partial(partial))
                }

                val now = System.currentTimeMillis()
                val silenceMs = now - lastVoiceMs
                val totalMs   = now - recordingStartMs

                // Stop when: (a) silence after speech, or (b) absolute max time reached
                if (silenceMs >= silenceTimeoutMs && lastPartial.isNotBlank()) break
                if (totalMs >= MAX_RECORD_MS) {
                    Log.i(TAG, "Max recording time reached — forcing final decode")
                    break
                }
            }
        } finally {
            val finalPcm = snapshot()
            val finalText = whisperEngine.transcribeFull(finalPcm)
            if (finalText.isNotBlank()) _flow.emit(TranscriptEvent.Final(finalText))
            rec.stop(); rec.release()
            isRecording.set(false)
        }
    }

    private fun append(chunk: ShortArray) {
        rollingBuffer.addLast(chunk); rollingBufferSamples += chunk.size
        while (rollingBufferSamples > maxRollingBufferSamples && rollingBuffer.isNotEmpty())
            rollingBufferSamples -= rollingBuffer.removeFirst().size
    }

    private fun snapshot(): FloatArray {
        val out = FloatArray(rollingBufferSamples); var i = 0
        for (c in rollingBuffer) for (s in c) { if (i >= out.size) break; out[i++] = s / 32768f }
        return out
    }

    /** Runs [rnNoise] over a Short PCM chunk, converting to/from the -1..1 float domain it expects. */
    private fun denoise(chunk: ShortArray): ShortArray {
        val floatIn = FloatArray(chunk.size) { chunk[it] / 32768f }
        val floatOut = try { rnNoise!!.processBuffer(floatIn) } catch (e: Exception) {
            Log.w(TAG, "Denoise failed, using raw audio: ${e.message}"); floatIn
        }
        return ShortArray(floatOut.size) { (floatOut[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun rms(frame: ShortArray): Float {
        val sum = frame.sumOf { (it.toLong() * it) }
        return kotlin.math.sqrt(sum.toDouble() / frame.size).toFloat()
    }

    fun release() { stopStreaming(); scope.cancel() }
}
