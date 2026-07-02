package com.aladdin.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aladdin.app.service.AladdinForegroundService

private const val TAG = "NotifActionReceiver"

/**
 * Handles actions triggered from Aladdin's ongoing notification buttons.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP = "com.aladdin.app.ACTION_STOP_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop action from notification")
                AladdinForegroundService.stop(context)
            }
        }
    }
}
