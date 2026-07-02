package com.aladdin.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

private const val TAG = "ScreenStateReceiver"

/**
 * ScreenStateReceiver — reacts to screen on/off events.
 *
 * Register this dynamically (not in manifest — ACTION_SCREEN_OFF/ON don't work
 * from static receivers) from [JarvisService.onCreate]:
 *
 *   val filter = ScreenStateReceiver.intentFilter()
 *   registerReceiver(screenStateReceiver, filter)
 *
 * The callbacks are invoked on the main thread and should be fast.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    var onScreenOff: (() -> Unit)? = null
    var onScreenOn:  (() -> Unit)? = null
    var onUserPresent: (() -> Unit)? = null   // fired when device is unlocked

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen OFF")
                onScreenOff?.invoke()
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen ON")
                onScreenOn?.invoke()
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User present (unlocked)")
                onUserPresent?.invoke()
            }
        }
    }

    companion object {
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
    }
}
