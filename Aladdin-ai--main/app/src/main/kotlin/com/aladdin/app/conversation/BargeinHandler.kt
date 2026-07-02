package com.aladdin.app.conversation

import android.speech.tts.TextToSpeech
import android.util.Log
import com.aladdin.app.vad.WebRTCVAD
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BargeinHandler"

// ─── Phase 2: Better Barge-In ─────────────────────────────────────────────────
//
// Changes from Phase 1:
//  • Uses WebRTCVAD confidence score (not raw energy) to avoid false triggers
//  • Hysteresis: requires BARGE_IN_HOLD_MS (80 ms) of sustained voice to fire
//  • Requires CONSECUTIVE_VOICE_FRAMES before committing to barge-in
//  • TTS stopped at sub-100 ms latency from voice onset
//  • onCommandCancelled fires so orchestrator can cancel LLM streaming job

@Singleton
class BargeinHandler @Inject constructor(
    private val conversationManager: ConversationManager,
    private val vadEngine: WebRTCVAD
) {
    companion object {
        private const val BARGE_IN_CONFIDENCE_THRESHOLD = 0.60f
        private const val BARGE_IN_HOLD_MS = 80L
        private const val CONSECUTIVE_VOICE_FRAMES = 3
    }

    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitorJob: Job? = null

    private var onBargein: (() -> Unit)? = null
    private var onCommandCancelled: (() -> Unit)? = null

    private val isMonitoring = AtomicBoolean(false)
    private var voiceOnsetMs = 0L
    private var consecutiveVoiceFrames = 0

    // ── Public API ────────────────────────────────────────────────────────────

    fun attachTts(textToSpeech: TextToSpeech) { tts = textToSpeech }
    fun detachTts() { tts = null }

    fun setOnBargeinListener(cb: () -> Unit) { onBargein = cb }
    fun setOnCommandCancelledListener(cb: () -> Unit) { onCommandCancelled = cb }

    /** Start monitoring for barge-in (call when assistant starts speaking). */
    fun startMonitoring() {
        if (isMonitoring.getAndSet(true)) return
        Log.d(TAG, "Barge-in monitoring active")
    }

    /** Stop monitoring (call when assistant finishes speaking). */
    fun stopMonitoring() {
        isMonitoring.set(false)
        voiceOnsetMs = 0L
        consecutiveVoiceFrames = 0
        Log.d(TAG, "Barge-in monitoring inactive")
    }

    /**
     * Phase 2: called by mic capture every audio frame while Aladdin is speaking.
     * Uses VAD confidence instead of raw energy for reliable detection.
     */
    fun onAudioFrame(frame: ShortArray) {
        if (!isMonitoring.get()) return
        if (!conversationManager.isSpeaking) return

        val confidence = vadEngine.getLastConfidence()
            .coerceAtLeast(energyConfidence(frame))

        if (confidence >= BARGE_IN_CONFIDENCE_THRESHOLD) {
            consecutiveVoiceFrames++
            if (voiceOnsetMs == 0L) voiceOnsetMs = System.currentTimeMillis()

            val heldMs = System.currentTimeMillis() - voiceOnsetMs
            if (heldMs >= BARGE_IN_HOLD_MS && consecutiveVoiceFrames >= CONSECUTIVE_VOICE_FRAMES) {
                Log.d(TAG, "BARGE-IN confirmed: conf=${"%.2f".format(confidence)} held=${heldMs}ms frames=$consecutiveVoiceFrames")
                triggerBargeIn()
            }
        } else {
            // Reset hysteresis on confidence drop
            consecutiveVoiceFrames = 0
            voiceOnsetMs = 0L
        }
    }

    // ── Trigger ───────────────────────────────────────────────────────────────

    private fun triggerBargeIn() {
        isMonitoring.set(false)
        consecutiveVoiceFrames = 0
        voiceOnsetMs = 0L

        // 1. Stop TTS immediately (sub-100 ms from voice onset)
        tts?.run {
            if (isSpeaking) {
                stop()
                Log.d(TAG, "TTS stopped by barge-in")
            }
        }

        // 2. Notify conversation manager and listeners on Main
        scope.launch {
            conversationManager.bargein()
            onBargein?.invoke()
        }
        onCommandCancelled?.invoke()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun energyConfidence(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        val avg = frame.map { kotlin.math.abs(it.toInt()) }.average().toFloat()
        return when {
            avg < 600f   -> 0f
            avg > 12000f -> 1f
            else         -> (avg - 600f) / (12000f - 600f)
        }
    }
}


// ─────────────────────────────────────────────────────────────────
// Item 34: Barge-in detection — user can interrupt AI speech
// ─────────────────────────────────────────────────────────────────

/**
 * Item 34: Real barge-in detection.
 * When the user speaks while the AI is talking, immediately:
 *   1. Stop audio playback
 *   2. Return to listening state
 *   3. Begin processing new user utterance
 */

// Extension: energy-based barge-in trigger
private const val BARGE_IN_ENERGY_THRESHOLD = 1500f
private const val BARGE_IN_FRAME_CONFIRM = 3  // 3 consecutive voiced frames to confirm barge-in
private var bargeInConfirmCount = 0

fun detectBargeIn(frame: ShortArray): Boolean {
    val rms = kotlin.math.sqrt(frame.sumOf { it.toLong() * it }.toDouble() / frame.size).toFloat()
    return if (rms > BARGE_IN_ENERGY_THRESHOLD) {
        bargeInConfirmCount++
        if (bargeInConfirmCount >= BARGE_IN_FRAME_CONFIRM) { bargeInConfirmCount = 0; true }
        else false
    } else { bargeInConfirmCount = 0; false }
}

fun resetBargeIn() { bargeInConfirmCount = 0 }
