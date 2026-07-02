package com.aladdin.reliability.watchdog

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that hosts the [ServiceWatchdog].
 * Must be declared in the host app's AndroidManifest.xml.
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "aladdin_watchdog"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchdogService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }

    private lateinit var watchdog: ServiceWatchdog

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        watchdog = ServiceWatchdog(applicationContext)
        // Host app should call watchdog.watch(...) via ReliabilityManager before starting
        watchdog.start()
        Log.i(TAG, "WatchdogService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // auto-restart by OS if killed
    }

    override fun onDestroy() {
        watchdog.stop()
        super.onDestroy()
        Log.i(TAG, "WatchdogService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun getWatchdog(): ServiceWatchdog = watchdog

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Aladdin Watchdog",
            NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Keeps Aladdin services running" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aladdin")
            .setContentText("Reliability watchdog active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}
