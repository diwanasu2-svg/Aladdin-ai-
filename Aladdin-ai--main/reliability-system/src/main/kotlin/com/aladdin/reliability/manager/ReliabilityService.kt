package com.aladdin.reliability.manager

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Phase 13 fix — ReliabilityService
 *
 * Foreground service that hosts the ReliabilityManager.
 * Declared in AndroidManifest.xml as a persistent service.
 * Complements WatchdogService for full reliability coverage.
 */
class ReliabilityService : Service() {

    companion object {
        private const val TAG = "ReliabilityService"
        private const val NOTIFICATION_ID = 9002
        private const val CHANNEL_ID = "aladdin_reliability"
        private const val CHANNEL_NAME = "Aladdin Reliability Monitor"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReliabilityService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReliabilityService::class.java))
        }
    }

    private lateinit var reliabilityManager: ReliabilityManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        reliabilityManager = ReliabilityManager(
            context = applicationContext,
            appVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        )
        Log.i(TAG, "ReliabilityService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        reliabilityManager.start(restartIntent = packageManager.getLaunchIntentForPackage(packageName))
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            reliabilityManager.stop()
        } catch (e: Exception) {
            Log.w(TAG, "ReliabilityManager stop error: ${e.message}")
        }
        super.onDestroy()
        Log.i(TAG, "ReliabilityService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Aladdin reliability and health monitoring"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aladdin Reliability Monitor")
            .setContentText("Health checks running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
