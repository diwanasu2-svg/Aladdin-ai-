package com.aladdin.app.jarvis

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.aladdin.app.service.AladdinForegroundService
import dagger.hilt.android.AndroidEntryPoint

private const val TAG = "JarvisService"

/**
 * JarvisService — DEPRECATED, kept only as an inert compatibility shim.
 *
 * Reliability fix (2026-07-08): this used to be a second, fully separate
 * "always-on" pipeline — its own BackgroundMicCapture + WakeWordDetector (backed
 * by a TFLite model that was never actually bundled in assets, see
 * aladdin_wakeword.tflite.README) + plain Android TextToSpeech with a hardcoded
 * "Yes, how can I help?" reply and no real STT/LLM call at all. It was started on
 * every boot (BootReceiver) while [AladdinForegroundService] — the service
 * actually wired to JarvisOrchestrator's real ONNX wake-word → STT → LLM → TTS
 * pipeline — was started separately whenever the app was opened. Both opened
 * their own exclusive AudioRecord session on the mic at the same time, so the
 * real pipeline's audio got starved/corrupted and wake-word detection silently
 * never fired — the underlying reason "always listening" didn't actually work
 * and typing felt like the only reliable option.
 *
 * All real call sites (BootReceiver, JarvisActionReceiver) now target
 * [AladdinForegroundService] directly. This class stays only so old
 * PendingIntents/manifest entries pointing at it can't crash — if the OS ever
 * does instantiate it, it immediately stops itself without touching the mic.
 */
@AndroidEntryPoint
class JarvisService : LifecycleService() {

    companion object {
        const val ACTION_START           = "com.aladdin.jarvis.START"
        const val ACTION_STOP            = "com.aladdin.jarvis.STOP"
        const val ACTION_START_LISTENING = "com.aladdin.jarvis.START_LISTENING"
        const val ACTION_PAUSE_TTS       = "com.aladdin.jarvis.PAUSE_TTS"
        const val ACTION_RESUME_TTS      = "com.aladdin.jarvis.RESUME_TTS"

        /** Forwards to the one real always-on service instead of running its own pipeline. */
        fun start(context: Context) = AladdinForegroundService.start(context)
        fun stop(context: Context) = AladdinForegroundService.stop(context)
    }

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "JarvisService is deprecated/inert — stopping immediately. " +
            "AladdinForegroundService owns the real always-on pipeline.")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
