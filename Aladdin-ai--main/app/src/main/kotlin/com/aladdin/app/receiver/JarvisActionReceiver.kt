package com.aladdin.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aladdin.app.service.AladdinForegroundService

private const val TAG = "JarvisActionReceiver"

/**
 * JarvisActionReceiver — handles button taps from the foreground-service notification.
 *
 * Reliability fix (2026-07-08): this used to target the legacy JarvisService, a
 * second, disconnected pipeline (plain Android TTS, hardcoded reply, no real LLM
 * call) that also fought AladdinForegroundService for exclusive microphone access.
 * Only ACTION_STOP_JARVIS maps to anything real now — stopping the one real
 * service (AladdinForegroundService, wired to JarvisOrchestrator). The always-on
 * pipeline already listens for the wake word and replies by voice continuously on
 * its own, so there's nothing useful left for a manual "start listening"/pause/
 * resume notification button to do; those actions are logged as no-ops instead of
 * silently starting a second broken pipeline.
 */
class JarvisActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MIC          = "com.aladdin.app.jarvis.ACTION_MIC"
        const val ACTION_PAUSE_TTS    = "com.aladdin.app.jarvis.ACTION_PAUSE_TTS"
        const val ACTION_RESUME_TTS   = "com.aladdin.app.jarvis.ACTION_RESUME_TTS"
        const val ACTION_STOP_JARVIS  = "com.aladdin.app.jarvis.ACTION_STOP_JARVIS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Action: ${intent.action}")
        when (intent.action) {
            ACTION_STOP_JARVIS -> AladdinForegroundService.stop(context)
            ACTION_MIC, ACTION_PAUSE_TTS, ACTION_RESUME_TTS ->
                Log.d(TAG, "${intent.action} is a no-op — Aladdin already listens/replies continuously")
        }
    }
}
