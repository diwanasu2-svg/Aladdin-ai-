package com.aladdin.tools.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aladdin.tools.tools.AlarmTool

/**
 * Receives alarm broadcasts from Android AlarmManager.
 * Shows a high-priority notification and plays the default ringtone.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "aladdin_alarms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.aladdin.tools.ALARM_TRIGGER" -> handleAlarm(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> Log.i(TAG, "Boot completed — reschedule persistent alarms here")
        }
    }

    private fun handleAlarm(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmTool.EXTRA_ALARM_ID, -1)
        val label = intent.getStringExtra(AlarmTool.EXTRA_ALARM_LABEL) ?: "Alarm"
        Log.i(TAG, "Alarm triggered: id=$alarmId '$label'")

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Aladdin alarm notifications"
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("⏰ Alarm: $label")
            .setContentText("Tap to dismiss")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .build()

        nm.notify(alarmId.toInt(), notification)
    }
}
