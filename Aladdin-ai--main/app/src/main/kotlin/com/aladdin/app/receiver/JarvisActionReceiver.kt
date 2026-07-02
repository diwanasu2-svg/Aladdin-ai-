package com.aladdin.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aladdin.app.jarvis.JarvisService

private const val TAG = "JarvisActionReceiver"

/**
 * JarvisActionReceiver — handles button taps from the MediaStyle notification.
 *
 * Actions:
 *  ACTION_MIC           → start a new listening session
 *  ACTION_PAUSE_TTS     → pause ongoing TTS
 *  ACTION_RESUME_TTS    → resume TTS
 *  ACTION_STOP_JARVIS   → stop the Jarvis service entirely
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
        val svc = Intent(context, JarvisService::class.java)
        when (intent.action) {
            ACTION_MIC         -> context.startService(svc.setAction(JarvisService.ACTION_START_LISTENING))
            ACTION_PAUSE_TTS   -> context.startService(svc.setAction(JarvisService.ACTION_PAUSE_TTS))
            ACTION_RESUME_TTS  -> context.startService(svc.setAction(JarvisService.ACTION_RESUME_TTS))
            ACTION_STOP_JARVIS -> context.startService(svc.setAction(JarvisService.ACTION_STOP))
        }
    }
}
