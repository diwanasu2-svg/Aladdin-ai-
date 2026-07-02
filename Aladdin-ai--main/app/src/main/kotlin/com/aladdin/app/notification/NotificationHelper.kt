package com.aladdin.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aladdin.app.MainActivity

private const val TAG = "NotificationHelper"

/**
 * NotificationHelper — central factory for all Aladdin notifications.
 *
 * Channels:
 *  - CHANNEL_FOREGROUND : ongoing service notification (low priority, no sound)
 *  - CHANNEL_ALERTS     : important user-facing alerts (default priority, sound + vibrate)
 *  - CHANNEL_SILENT     : silent background sync (min priority, no heads-up)
 *
 * Call [createAllChannels] once at app startup (from [AladdinApp.onCreate]).
 */
object NotificationHelper {

    const val CHANNEL_FOREGROUND = "aladdin_foreground"
    const val CHANNEL_ALERTS     = "aladdin_alerts"
    const val CHANNEL_SILENT     = "aladdin_silent"

    fun createAllChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_FOREGROUND,
                    "Aladdin Assistant",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Aladdin running in the background"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                },

                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Alerts & Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Task alerts, reminders and important updates"
                    enableLights(true)
                    enableVibration(true)
                    lightColor = 0xFF6200EE.toInt()
                },

                NotificationChannel(
                    CHANNEL_SILENT,
                    "Background Updates",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Silent background sync notifications"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        )
        Log.d(TAG, "Notification channels created")
    }

    // ─── Builders ─────────────────────────────────────────────────────────────

    fun buildAlertNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = System.currentTimeMillis().toInt()
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context, notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    fun buildSilentNotification(context: Context, title: String, message: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SILENT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

    // ─── Show helpers ─────────────────────────────────────────────────────────

    fun showAlert(context: Context, title: String, message: String) {
        val id = System.currentTimeMillis().toInt()
        try {
            NotificationManagerCompat.from(context)
                .notify(id, buildAlertNotification(context, title, message, id))
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted", e)
        }
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
