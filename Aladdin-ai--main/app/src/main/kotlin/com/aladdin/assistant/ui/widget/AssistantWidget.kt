package com.aladdin.assistant.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.aladdin.app.MainActivity
import com.aladdin.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Phase 6 Item 6: Home Screen Widget — Fixed & Enhanced ───────────────────

class AssistantWidgetReceiver : AppWidgetProvider() {

    companion object {
        private const val TAG                 = "AssistantWidget"
        private const val ACTION_WIDGET_UPDATE = "com.aladdin.assistant.WIDGET_UPDATE"
        private const val UPDATE_INTERVAL_MS   = 30L * 60_000L  // 30 minutes

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val greeting = timeBasedGreeting()

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent, pendingFlags()
            )

            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                action = "ACTION_VOICE_INPUT"
                flags  = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val voicePendingIntent = PendingIntent.getActivity(
                context, 1, voiceIntent, pendingFlags()
            )

            val reminderIntent = Intent(context, MainActivity::class.java).apply {
                action = "ACTION_QUICK_REMINDER"
                flags  = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val reminderPendingIntent = PendingIntent.getActivity(
                context, 2, reminderIntent, pendingFlags()
            )

            val views = RemoteViews(context.packageName, R.layout.assistant_widget)
            views.setTextViewText(R.id.tv_widget_title,  "Aladdin AI")
            views.setTextViewText(R.id.tv_widget_status, greeting)

            views.setOnClickPendingIntent(R.id.widget_root,        openPendingIntent)
            views.setOnClickPendingIntent(R.id.btn_widget_voice,    voicePendingIntent)
            views.setOnClickPendingIntent(R.id.btn_widget_reminder, reminderPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget #$appWidgetId updated — greeting='$greeting'")
        }

        private fun timeBasedGreeting(): String {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return when {
                hour < 12 -> "Good morning"
                hour < 17 -> "Good afternoon"
                hour < 21 -> "Good evening"
                else      -> "Good night"
            } + "  ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
        }

        private fun pendingFlags() =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun schedulePeriodicUpdate(context: Context) {
            val intent = Intent(context, AssistantWidgetReceiver::class.java).apply {
                action = ACTION_WIDGET_UPDATE
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 99, intent, pendingFlags())
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS,
                UPDATE_INTERVAL_MS,
                pendingIntent
            )
            Log.i(TAG, "Periodic widget update alarm scheduled (${UPDATE_INTERVAL_MS / 60_000} min)")
        }

        fun cancelPeriodicUpdate(context: Context) {
            val intent = Intent(context, AssistantWidgetReceiver::class.java).apply {
                action = ACTION_WIDGET_UPDATE
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 99, intent, pendingFlags())
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(pendingIntent)
            Log.i(TAG, "Periodic widget update alarm cancelled")
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_UPDATE) {
            Log.d(TAG, "Received periodic update broadcast")
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AssistantWidgetReceiver::class.java))
            for (id in ids) updateAppWidget(context, manager, id)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicUpdate(context)
        Log.i(TAG, "First widget added — periodic updates started")
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelPeriodicUpdate(context)
        Log.i(TAG, "Last widget removed — periodic updates cancelled")
    }
}
