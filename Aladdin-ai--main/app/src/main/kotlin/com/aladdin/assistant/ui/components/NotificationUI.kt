package com.aladdin.assistant.ui.components

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aladdin.assistant.ui.MainActivity

// ─── Phase 6 Item 3: Media-Style Notification — Fixed & Enhanced ─────────────
// Changes applied:
//   • HIGH-importance foreground channel with proper description
//   • 3 action buttons: Mute / Stop / Settings (no icons for backward compat)
//   • State-aware update: buildForegroundNotification accepts AssistantState enum
//   • updateNotificationState() called from service on every state change
//   • PendingIntent flags: FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE on API 23+

object NotificationChannels {
    const val AI_STATUS      = "ai_status"
    const val AI_REPLY       = "ai_reply"
    const val VOICE_LISTENING = "voice_listening"
    const val PROGRESS       = "progress"

    /** Call once from Application.onCreate() */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // Foreground service channel — HIGH so it appears above other status-bar icons
        manager.createNotificationChannel(
            NotificationChannel(AI_STATUS, "Aladdin AI Status",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Persistent foreground notification while Aladdin AI is active"
                setShowBadge(false)
                enableVibration(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(AI_REPLY, "Aladdin AI Replies",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications when the assistant responds to a request"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(VOICE_LISTENING, "Voice Listening",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status while Aladdin is listening for your voice"
                enableVibration(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(PROGRESS, "AI Processing",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI background processing progress"
                enableVibration(false)
            }
        )
    }
}

enum class AssistantNotificationState {
    IDLE, LISTENING, PROCESSING, SPEAKING
}

object AladdinNotificationBuilder {

    const val FOREGROUND_ID = 1001
    private const val REPLY_ID    = 1002
    private const val PROGRESS_ID = 1003

    private fun pendingFlags() =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    // ── Action PendingIntents ─────────────────────────────────────────────────

    private fun openIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )

    private fun muteIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).apply {
                action = "ACTION_MUTE_AI"
                flags  = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )

    private fun stopIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 11,
            Intent(context, MainActivity::class.java).apply {
                action = "ACTION_STOP_AI"
                flags  = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )

    private fun settingsIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 12,
            Intent(context, MainActivity::class.java).apply {
                action = "ACTION_OPEN_SETTINGS"
                flags  = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )

    // ── Foreground notification ───────────────────────────────────────────────

    /**
     * Build the persistent foreground service notification.
     * Call [updateNotificationState] to refresh it when the assistant state changes.
     */
    fun buildForegroundNotification(
        context: Context,
        state: AssistantNotificationState = AssistantNotificationState.IDLE
    ): android.app.Notification {
        val (title, body) = when (state) {
            AssistantNotificationState.IDLE       -> "Aladdin AI is ready"      to "Tap to open"
            AssistantNotificationState.LISTENING  -> "Aladdin is listening…"    to "Speak your command"
            AssistantNotificationState.PROCESSING -> "Aladdin is thinking…"     to "Processing your request"
            AssistantNotificationState.SPEAKING   -> "Aladdin is speaking…"     to "AI response in progress"
        }

        return NotificationCompat.Builder(context, NotificationChannels.AI_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            // 3 action buttons — no icons for backward compatibility (API < 23 renders icons badly)
            .addAction(0, "Mute",     muteIntent(context))
            .addAction(0, "Stop",     stopIntent(context))
            .addAction(0, "Settings", settingsIntent(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * Update the persistent foreground notification to reflect a new [state].
     * Call this from the foreground service whenever voiceState changes.
     */
    fun updateNotificationState(context: Context, state: AssistantNotificationState) {
        val notification = buildForegroundNotification(context, state)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(FOREGROUND_ID, notification)
    }

    // ── AI reply notification ─────────────────────────────────────────────────

    fun showAIReply(context: Context, conversationTitle: String, reply: String) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.AI_REPLY)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Aladdin — $conversationTitle")
            .setContentText(reply)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            // Action buttons — no icons
            .addAction(0, "Reply by Voice", openIntent(context))
            .addAction(0, "Open Chat",      openIntent(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(REPLY_ID, notification)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not yet granted */ }
    }

    // ── Listening notification ────────────────────────────────────────────────

    fun showListeningNotification(context: Context) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.VOICE_LISTENING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Aladdin is Listening")
            .setContentText("Speak now…")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(FOREGROUND_ID + 10, notification)
        } catch (_: SecurityException) {}
    }

    // ── Progress notification ─────────────────────────────────────────────────

    fun showProgressNotification(context: Context, progress: Int, message: String) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.PROGRESS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Aladdin is thinking…")
            .setContentText(message)
            .setProgress(100, progress, progress == 0)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(PROGRESS_ID, notification)
        } catch (_: SecurityException) {}
    }

    fun dismissProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
    }
}
