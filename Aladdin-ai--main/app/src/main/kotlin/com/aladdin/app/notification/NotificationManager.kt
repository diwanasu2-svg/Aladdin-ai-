package com.aladdin.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AladdinNotificationManager — Item 72: Notification channels, scheduling, reminders.
 */
@Singleton
class AladdinNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AladdinNotif"

        const val CHANNEL_ASSISTANT  = "aladdin_assistant"
        const val CHANNEL_REMINDERS  = "aladdin_reminders"
        const val CHANNEL_BRIEFINGS  = "aladdin_briefings"
        const val CHANNEL_ALERTS     = "aladdin_alerts"
        const val CHANNEL_SILENT     = "aladdin_silent"
    }

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { createChannels() }

    // ── Channel creation ──────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        listOf(
            NotificationChannel(CHANNEL_ASSISTANT, "Aladdin Assistant",   NotificationManager.IMPORTANCE_DEFAULT).also { it.description = "Assistant responses and status" },
            NotificationChannel(CHANNEL_REMINDERS, "Reminders",           NotificationManager.IMPORTANCE_HIGH).also    { it.description = "Scheduled reminders and alarms" },
            NotificationChannel(CHANNEL_BRIEFINGS, "Daily Briefings",     NotificationManager.IMPORTANCE_DEFAULT).also { it.description = "Morning briefings and digests" },
            NotificationChannel(CHANNEL_ALERTS,    "Security Alerts",     NotificationManager.IMPORTANCE_HIGH).also    { it.description = "Critical security notifications" },
            NotificationChannel(CHANNEL_SILENT,    "Background Service",  NotificationManager.IMPORTANCE_LOW).also     { it.description = "Silent foreground service notification" }
        ).forEach { nm.createNotificationChannel(it) }
        Log.d(TAG, "Notification channels created")
    }

    // ── Show notification ─────────────────────────────────────────────────────

    fun showNotification(
        id: Int,
        title: String,
        body: String,
        channel: String = CHANNEL_ASSISTANT,
        autoCancel: Boolean = true,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        intent: Intent? = null,
        iconRes: Int = android.R.drawable.ic_dialog_info
    ) {
        val pi = intent?.let {
            PendingIntent.getActivity(context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(priority)
            .setAutoCancel(autoCancel)
            .apply { if (pi != null) setContentIntent(pi) }

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            Log.d(TAG, "Notification shown: id=$id title='$title'")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }

    fun showReminderNotification(id: Int, title: String, body: String) =
        showNotification(id, title, body, CHANNEL_REMINDERS, priority = NotificationCompat.PRIORITY_HIGH)

    fun showBriefingNotification(id: Int, body: String) =
        showNotification(id, "Good Morning — Daily Briefing", body, CHANNEL_BRIEFINGS)

    fun showSecurityAlert(id: Int, message: String) =
        showNotification(id, "Security Alert", message, CHANNEL_ALERTS, priority = NotificationCompat.PRIORITY_MAX)

    fun showForegroundNotification(title: String, body: String): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        return builder.build()
    }

    fun cancel(id: Int) { nm.cancel(id) }
    fun cancelAll() { nm.cancelAll() }

    fun hasPermission(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
