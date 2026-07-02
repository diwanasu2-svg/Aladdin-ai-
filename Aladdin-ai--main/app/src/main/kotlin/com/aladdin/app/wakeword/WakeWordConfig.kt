package com.aladdin.app.wakeword

import javax.inject.Inject
import javax.inject.Singleton

/**
 * WakeWordConfig — tuneable parameters for the wake-word detector.
 *
 * Adjust these to balance sensitivity vs false-positive rate.
 */
@Singleton
class WakeWordConfig @Inject constructor() {

    /** The keyword to listen for (case-insensitive label, no audio data). */
    val keyword: String = "ALADDIN"

    /**
     * Minimum average absolute amplitude for a frame to be considered non-silent.
     * Range: 0–32767 (ShortArray PCM). Increase to reject noisy environments.
     */
    val energyThreshold: Float = 800f

    /**
     * Score (0–1) above which a single frame is considered "keyword-like".
     * Lower = more sensitive, higher = more selective.
     */
    val detectionThreshold: Float = 0.55f

    /**
     * Number of consecutive 20-ms frames in the sliding vote window.
     * 15 frames × 20 ms = 300 ms minimum keyword duration.
     */
    val votingWindowFrames: Int = 15

    /**
     * Minimum milliseconds between two consecutive wake events.
     * Prevents double-triggers on the same utterance.
     */
    val cooldownMs: Long = 2_000L

    /**
     * Whether to keep the detector active when the screen turns off.
     * true  = always-on (Jarvis mode), uses PARTIAL_WAKE_LOCK
     * false = pause when screen off (saves battery)
     */
    val listenOnScreenOff: Boolean = true
}
