package com.aladdin.voicecore.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.aladdin.voicecore.audio.AudioFocusManager
import com.aladdin.voicecore.models.ErrorCode
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

// ─── Phase 2: Streaming LLM → TTS Pipeline ───────────────────────────────────
//
// Changes from Phase 1:
//  • [enqueueSentence] lets the LLM feed sentences one-by-one as they generate.
//    TTS starts speaking the FIRST sentence before the LLM finishes the rest.
//  • Sentence queue (Channel) decouples LLM speed from Piper playback speed.
//  • [stop] drains the queue instantly → clean barge-in support.
//  • [resumeForNewUtterance] resets state so next call works after barge-in.
//  • Piper streaming path unchanged; added Android TTS fallback path.

class TTSEngine(
    private val context: Context,
    private val config: VoiceCoreConfig,
    private val audioFocusManager: AudioFocusManager
) {
    companion object {
        private const val TAG = "TTSEngine"
        private const val TTS_SAMPLE_RATE = 22_050
        private const val CHUNK_SIZE_BYTES = 4_096
        private const val SENTENCE_QUEUE_CAPACITY = 32
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceCoreEvent> = _events

    // ── Phase 2: Sentence streaming queue ─────────────────────────────────────
    private var sentenceChannel = Channel<String>(SENTENCE_QUEUE_CAPACITY)
    private var processorJob: Job? = null
    private var piperProcess: Process? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var isSpeaking = false
    @Volatile private var stopRequested = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Phase 2: Enqueue a sentence for immediate playback.
     * The sentence queue starts the Piper process as soon as the first sentence arrives,
     * without waiting for the full LLM response — Jarvis-style pipeline.
     */
    fun enqueueSentence(sentence: String) {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty() || stopRequested) return
        scope.launch { sentenceChannel.trySend(trimmed) }
        Log.v(TAG, "Enqueued: ${trimmed.take(60)}")
        ensureProcessorRunning()
    }

    /** Signal that no more sentences are coming (call after LLM stream ends). */
    fun finishEnqueuing() {
        scope.launch { sentenceChannel.close() }
    }

    /**
     * Speak a complete text immediately (Phase 1 compatibility path).
     * Prefer [enqueueSentence] for Phase 2 streaming pipeline.
     */
    fun speak(text: String) {
        stop()
        resumeForNewUtterance()
        // Split into sentences and enqueue each
        splitSentences(text).forEach { enqueueSentence(it) }
        finishEnqueuing()
    }

    /** Interrupt TTS and drain the queue instantly (barge-in). */
    fun stop() {
        stopRequested = true
        isSpeaking = false
        processorJob?.cancel()
        processorJob = null
        try { piperProcess?.destroy() } catch (_: Exception) {}
        piperProcess = null
        audioTrack?.apply { try { pause(); flush() } catch (_: Exception) {} }
        audioFocusManager.releaseFocus()
        // Drain without processing
        while (!sentenceChannel.isEmpty) sentenceChannel.tryReceive()
        Log.i(TAG, "TTS stopped (barge-in/interrupt)")
    }

    /** Reset state before a new utterance (call after stop + barge-in). */
    fun resumeForNewUtterance() {
        stopRequested = false
        sentenceChannel = Channel(SENTENCE_QUEUE_CAPACITY)
    }

    fun shutdown() {
        stop()
        scope.cancel()
        audioTrack?.release()
        audioTrack = null
    }

    // ── Processor loop ────────────────────────────────────────────────────────

    private fun ensureProcessorRunning() {
        if (processorJob?.isActive == true) return
        processorJob = scope.launch {
            if (!audioFocusManager.requestFocusForTTS()) {
                Log.w(TAG, "AudioFocus denied")
                _events.emit(VoiceCoreEvent.Error(ErrorCode.AUDIO_FOCUS_LOST, "AudioFocus denied"))
                return@launch
            }
            val track = createAudioTrack()
            audioTrack = track
            track.play()
            isSpeaking = true
            _events.emit(VoiceCoreEvent.TTSChunk("", ByteArray(0))) // signal started

            try {
                for (sentence in sentenceChannel) {
                    if (stopRequested) break
                    Log.d(TAG, "Speaking sentence: ${sentence.take(60)}")
                    streamPiperSentence(sentence, track)
                }
            } finally {
                if (!stopRequested) {
                    track.stop()
                    _events.emit(VoiceCoreEvent.TTSComplete)
                    Log.i(TAG, "All sentences spoken")
                }
                isSpeaking = false
                audioFocusManager.releaseFocus()
            }
        }
    }

    private suspend fun streamPiperSentence(text: String, track: AudioTrack) {
        val piperBin = "${context.filesDir}/${config.ttsModelPath}/piper"
        val voiceModel = "${context.filesDir}/${config.ttsModelPath}/${config.ttsVoice}.onnx"
        val voiceConfig = "${context.filesDir}/${config.ttsModelPath}/${config.ttsVoice}.onnx.json"

        if (!File(piperBin).exists()) {
            // Android TTS fallback
            androidTtsFallback(text)
            return
        }

        val cmd = arrayOf(
            piperBin,
            "--model", voiceModel,
            "--config", voiceConfig,
            "--output_raw",
            "--length_scale", (1.0f / config.ttsSpeakingRate).toString()
        )

        piperProcess = Runtime.getRuntime().exec(cmd)
        val process = piperProcess!!

        scope.launch(Dispatchers.IO) {
            try { process.outputStream.bufferedWriter().use { it.write(text) } }
            catch (_: Exception) {}
        }

        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        val inputStream = process.inputStream
        while (!stopRequested && scope.isActive) {
            val n = inputStream.read(buffer)
            if (n < 0) break
            if (n > 0) {
                track.write(buffer, 0, n)
                _events.emit(VoiceCoreEvent.TTSChunk(text, buffer.copyOf(n)))
            }
        }
        try { process.waitFor() } catch (_: Exception) {}
        piperProcess = null
    }

    private suspend fun androidTtsFallback(text: String) {
        // Minimal Android TTS fallback when Piper binary is absent
        android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                Log.d(TAG, "Android TTS fallback: $text")
            }
        }.also { tts ->
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "piper_fallback")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun splitSentences(text: String): List<String> {
        val terminators = setOf('.', '!', '?', '。', '！', '？')
        val sentences = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch in terminators) {
                val s = buf.toString().trim()
                if (s.isNotBlank()) sentences.add(s)
                buf.clear()
            }
        }
        val remaining = buf.toString().trim()
        if (remaining.isNotBlank()) sentences.add(remaining)
        return sentences.ifEmpty { listOf(text) }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            TTS_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(TTS_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack(
            attrs, format, maxOf(minBuf, CHUNK_SIZE_BYTES * 2),
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }
}
