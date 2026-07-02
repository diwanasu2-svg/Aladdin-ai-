package com.aladdin.assistant.bargein

import android.util.Log
import com.aladdin.assistant.tts.StreamingTTS
import com.aladdin.assistant.vad.VADEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 – Better Barge-In Manager
 *
 * Monitors VAD confidence while the assistant is speaking.
 * Fires barge-in after BARGE_IN_HOLD_MS (80 ms) of sustained voice confidence
 * above threshold — sub-100 ms reaction time from voice onset.
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
    }

    interface BargeInListener { fun onBargeIn() }

    var listener: BargeInListener? = null
    private val assistantSpeaking = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null

    fun onAssistantStartedSpeaking() { assistantSpeaking.set(true); startMonitor() }
    fun onAssistantStoppedSpeaking() { assistantSpeaking.set(false); stopMonitor() }

    fun release() { stopMonitor(); scope.cancel() }

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

    private fun trigger() {
        assistantSpeaking.set(false)
        tts.stopSpeaking()
        CoroutineScope(Dispatchers.Main).launch { listener?.onBargeIn() }
        stopMonitor()
    }
}
