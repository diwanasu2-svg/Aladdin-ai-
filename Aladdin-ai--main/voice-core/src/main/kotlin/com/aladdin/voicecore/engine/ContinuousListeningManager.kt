package com.aladdin.voicecore.engine

import android.util.Log
import com.aladdin.voicecore.audio.AudioPipeline
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import com.aladdin.voicecore.models.VoiceCoreState
import com.aladdin.voicecore.speech.STTEngine
import com.aladdin.voicecore.speech.TTSEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates always-listening behaviour for zero-touch voice interaction.
 *
 * State machine:
 *
 *   SLEEP ──wake()──► LISTENING_FOR_WAKE_WORD
 *                          │
 *                     wake word detected
 *                          │
 *                          ▼
 *                    CAPTURING_SPEECH ──silence timeout──► PROCESSING_STT
 *                          │                                    │
 *                     barge-in (VAD)                    transcript emitted
 *                          │                                    │
 *                          ▼                                    ▼
 *                    (interrupt TTS)                    SPEAKING_TTS
 *                                                            │
 *                                                  TTS complete / stop()
 *                                                            │
 *                                        conversationMode? CAPTURING_SPEECH
 *                                                    else: LISTENING_FOR_WAKE_WORD
 *
 * Sleep mode:
 *   - Entered after [VoiceCoreConfig.sleepAfterIdleMs] with no interaction.
 *   - Pipeline is muted (mic stays open to save AudioRecord setup cost).
 *   - Exited on [wakeUp] or when the wake word re-triggers.
 */
class ContinuousListeningManager(
    private val config: VoiceCoreConfig,
    private val audioPipeline: AudioPipeline,
    private val wakeWordEngine: WakeWordEngine,
    private val sttEngine: STTEngine,
    private val ttsEngine: TTSEngine,
    externalScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CLManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + externalScope.coroutineContext)

    private val _state = MutableStateFlow(VoiceCoreState.IDLE)
    val state: StateFlow<VoiceCoreState> = _state

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceCoreEvent> = _events

    @Volatile var conversationMode: Boolean = config.conversationModeEnabled

    private var sleepTimerJob: Job? = null
    private var lastInteractionMs = System.currentTimeMillis()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        _state.value = VoiceCoreState.LISTENING_FOR_WAKE_WORD
        resetSleepTimer()
        collectWakeWordEvents()
        collectSTTEvents()
        collectTTSEvents()
        collectPipelineEvents()
        Log.i(TAG, "ContinuousListeningManager started")
    }

    fun stop() {
        scope.cancel()
        _state.value = VoiceCoreState.IDLE
        Log.i(TAG, "ContinuousListeningManager stopped")
    }

    /** Manually wake from sleep mode. */
    fun wakeUp() {
        if (_state.value == VoiceCoreState.SLEEP) {
            Log.i(TAG, "Manual wake-up")
            exitSleepMode()
        }
    }

    /** Force-stop any current TTS (VAD barge-in). */
    fun bargein() {
        if (_state.value == VoiceCoreState.SPEAKING_TTS) {
            Log.i(TAG, "Barge-in: interrupting TTS")
            ttsEngine.stop()
            transitionTo(VoiceCoreState.CAPTURING_SPEECH)
            sttEngine.startListening()
        }
    }

    // ─── Event Collectors ─────────────────────────────────────────────────────

    private fun collectWakeWordEvents() {
        scope.launch {
            wakeWordEngine.events.collect { event ->
                when (event) {
                    is VoiceCoreEvent.WakeWordDetected -> {
                        Log.i(TAG, "Wake word '${event.keyword}' (${event.confidence})")
                        _events.emit(event)
                        lastInteractionMs = System.currentTimeMillis()
                        if (_state.value == VoiceCoreState.SLEEP) exitSleepMode()
                        if (_state.value == VoiceCoreState.LISTENING_FOR_WAKE_WORD) {
                            transitionTo(VoiceCoreState.CAPTURING_SPEECH)
                            sttEngine.startListening()
                            resetSleepTimer()
                        }
                    }
                    else -> _events.emit(event)
                }
            }
        }
    }

    private fun collectSTTEvents() {
        scope.launch {
            sttEngine.events.collect { event ->
                _events.emit(event)
                when (event) {
                    is VoiceCoreEvent.Transcript -> {
                        if (event.isFinal) {
                            lastInteractionMs = System.currentTimeMillis()
                            transitionTo(VoiceCoreState.PROCESSING_STT)
                        }
                    }
                    is VoiceCoreEvent.SpeechEnded -> {
                        if (_state.value == VoiceCoreState.CAPTURING_SPEECH) {
                            transitionTo(VoiceCoreState.PROCESSING_STT)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectTTSEvents() {
        scope.launch {
            ttsEngine.events.collect { event ->
                _events.emit(event)
                when (event) {
                    is VoiceCoreEvent.TTSComplete -> {
                        lastInteractionMs = System.currentTimeMillis()
                        if (config.autoResumeAfterTTS) {
                            if (conversationMode) {
                                Log.i(TAG, "TTS complete – resuming in conversation mode")
                                transitionTo(VoiceCoreState.CAPTURING_SPEECH)
                                sttEngine.startListening()
                            } else {
                                Log.i(TAG, "TTS complete – returning to wake word listening")
                                transitionTo(VoiceCoreState.LISTENING_FOR_WAKE_WORD)
                            }
                            audioPipeline.unmute()
                            resetSleepTimer()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectPipelineEvents() {
        scope.launch {
            audioPipeline.events.collect { event ->
                _events.emit(event)
            }
        }
    }

    // ─── TTS Entry Point ──────────────────────────────────────────────────────

    /**
     * Called by the host app to speak a response.
     * Mutes the mic during TTS to prevent echo (AEC fallback).
     */
    fun speak(text: String) {
        transitionTo(VoiceCoreState.SPEAKING_TTS)
        audioPipeline.mute()
        ttsEngine.speak(text)
        lastInteractionMs = System.currentTimeMillis()
        resetSleepTimer()
    }

    // ─── Sleep Mode ───────────────────────────────────────────────────────────

    private fun resetSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            delay(config.sleepAfterIdleMs)
            val idleMs = System.currentTimeMillis() - lastInteractionMs
            if (idleMs >= config.sleepAfterIdleMs) {
                enterSleepMode()
            }
        }
    }

    private fun enterSleepMode() {
        if (_state.value == VoiceCoreState.SLEEP) return
        Log.i(TAG, "Entering sleep mode after ${config.sleepAfterIdleMs}ms idle")
        transitionTo(VoiceCoreState.SLEEP)
        audioPipeline.mute()
        scope.launch { _events.emit(VoiceCoreEvent.SleepModeEntered) }
    }

    private fun exitSleepMode() {
        Log.i(TAG, "Exiting sleep mode")
        audioPipeline.unmute()
        transitionTo(VoiceCoreState.LISTENING_FOR_WAKE_WORD)
        scope.launch { _events.emit(VoiceCoreEvent.SleepModeExited) }
        resetSleepTimer()
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private fun transitionTo(newState: VoiceCoreState) {
        val old = _state.value
        if (old == newState) return
        _state.value = newState
        Log.d(TAG, "State: $old → $newState")
    }
}
