package com.aladdin.assistant.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 – Streaming STT
 * Captures microphone audio and emits partial + final transcripts in real-time.
 * Whisper.cpp is used for on-device inference via [WhisperEngine].
 * Silence detection fires at 350 ms (configurable via [silenceTimeoutMs]).
 */
class StreamingSTT(
    private val context: Context,
    private val whisperEngine: WhisperEngine
) {
    companion object {
        private const val TAG = "StreamingSTT"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_MS = 300L
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
        private const val MAX_BUFFER_SEC = 30
    }

    var silenceTimeoutMs: Long = 350L

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

        try {
            while (isRecording.get() && isActive) {
                val read = rec.read(buf, 0, CHUNK_SAMPLES)
                if (read <= 0) { delay(5); continue }
                val chunk = buf.copyOf(read)
                val rms = rms(chunk)
                if (rms > 150f) lastVoiceMs = System.currentTimeMillis()

                append(chunk)

                val pcm = snapshot()
                val partial = whisperEngine.transcribePartial(pcm)
                if (partial.isNotBlank() && partial != lastPartial) {
                    lastPartial = partial
                    _flow.emit(TranscriptEvent.Partial(partial))
                }

                // Silence timeout
                if (System.currentTimeMillis() - lastVoiceMs >= silenceTimeoutMs && lastPartial.isNotBlank()) {
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

    private fun rms(frame: ShortArray): Float {
        val sum = frame.sumOf { (it.toLong() * it) }
        return kotlin.math.sqrt(sum.toDouble() / frame.size).toFloat()
    }

    fun release() { stopStreaming(); scope.cancel() }
}
