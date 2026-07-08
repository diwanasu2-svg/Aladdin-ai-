package com.aladdin.assistant.bargein

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aladdin.assistant.tts.StreamingTTS
import com.aladdin.assistant.vad.VADEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 – Better Barge-In Manager
 *
 * Monitors VAD confidence while the assistant is speaking, so the user can
 * just start talking to interrupt Aladdin mid-sentence (no button press).
 * Fires barge-in after BARGE_IN_HOLD_MS (80 ms) of sustained voice confidence
 * above threshold — sub-100 ms reaction time from voice onset.
 *
 * E2E test fix (2026-07-08): this class only ever *collected*
 * vadEngine.confidenceFlow — nothing anywhere in the app ever called
 * vadEngine.processFrame(pcm) with live microphone audio, so confidenceFlow
 * sat at 0f forever and barge-in could never fire; talking over Aladdin while
 * it was speaking silently did nothing. Now this class owns a small
 * dedicated AudioRecord loop (mirrors StreamingSTT's format/config) that only
 * runs while the assistant is actually speaking, feeding real audio into the
 * VAD so barge-in works as designed.
 */
class BargeInManager(
    private val vadEngine: VADEngine,
    private val tts: StreamingTTS
) {
    companion object {
        private const val TAG = "BargeInManager"
        private const val CONFIDENCE_THRESHOLD = 0.60f
        private const val HOLD_MS = 80L
        private const val CONSECUTIVE_FRAMES = 3

        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_MS = 100L
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
    }

    interface BargeInListener { fun onBargeIn() }

    var listener: BargeInListener? = null
    private val assistantSpeaking = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    private var micJob: Job? = null

    fun onAssistantStartedSpeaking() {
        assistantSpeaking.set(true)
        vadEngine.reset()
        startMonitor()
        startMicFeed()
    }

    fun onAssistantStoppedSpeaking() {
        assistantSpeaking.set(false)
        stopMonitor()
        stopMicFeed()
    }

    fun release() { stopMonitor(); stopMicFeed(); scope.cancel() }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var onsetMs = 0L; var consecutive = 0
            vadEngine.confidenceFlow.collect { confidence ->
                if (!assistantSpeaking.get()) return@collect
                if (confidence >= CONFIDENCE_THRESHOLD) {
                    consecutive++
                    if (onsetMs == 0L) onsetMs = System.currentTimeMillis()
                    val held = System.currentTimeMillis() - onsetMs
                    if (held >= HOLD_MS && consecutive >= CONSECUTIVE_FRAMES) {
                        Log.d(TAG, "BARGE-IN conf=${"%.2f".format(confidence)} held=${held}ms")
                        trigger()
                    }
                } else { consecutive = 0; onsetMs = 0L }
            }
        }
    }

    private fun stopMonitor() { monitorJob?.cancel(); monitorJob = null }

    /**
     * Dedicated mic capture that feeds [VADEngine.processFrame] while (and only
     * while) the assistant is speaking. Separate AudioRecord session from
     * wake-word/STT, but those are never active at the same time as TTS
     * playback in the orchestrator's state machine, so there's no contention
     * for the mic — only one of {wake-word, STT, barge-in VAD} ever records
     * at once.
     */
    private fun startMicFeed() {
        micJob?.cancel()
        micJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK_SAMPLES * 4)
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "No RECORD_AUDIO permission — barge-in disabled: ${e.message}")
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed — barge-in disabled")
                return@launch
            }
            try {
                rec.startRecording()
                val buf = ShortArray(CHUNK_SAMPLES)
                while (isActive && assistantSpeaking.get()) {
                    val read = rec.read(buf, 0, CHUNK_SAMPLES)
                    if (read <= 0) { delay(5); continue }
                    val pcm = FloatArray(read) { buf[it] / 32768f }
                    vadEngine.processFrame(pcm)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Barge-in mic loop error: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
    }

    private fun stopMicFeed() { micJob?.cancel(); micJob = null }

    private fun trigger() {
        assistantSpeaking.set(false)
        stopMicFeed()
        tts.stopSpeaking()
        CoroutineScope(Dispatchers.Main).launch { listener?.onBargeIn() }
        stopMonitor()
    }
}
