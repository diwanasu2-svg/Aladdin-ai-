package com.aladdin.assistant.ui.components

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object AppNotificationChannels {

    const val CHANNEL_ASSISTANT = "aladdin_assistant"
    const val CHANNEL_VOICE     = "aladdin_voice"
    const val CHANNEL_ALERTS    = "aladdin_alerts"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASSISTANT,
                "Aladdin Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing assistant service notification"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VOICE,
                "Voice Interaction",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Voice listening and response notifications"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Aladdin Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important alerts and reminders from Aladdin"
            }
        )
    }
}
