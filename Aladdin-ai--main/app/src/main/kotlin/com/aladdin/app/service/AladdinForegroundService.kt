package com.aladdin.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aladdin.app.MainActivity
import com.aladdin.app.notification.NotificationHelper
import com.aladdin.assistant.orchestrator.JarvisOrchestrator
import com.aladdin.app.receiver.NotificationActionReceiver
import com.aladdin.app.wakeword.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

private const val TAG = "AladdinFgService"
private const val NOTIFICATION_ID = 1001
private const val WAKE_LOCK_TAG = "Aladdin:ForegroundWakeLock"

/**
 * AladdinForegroundService
 *
 * - Runs as an Android foreground service (survives task removal and screen off)
 * - Holds a PARTIAL_WAKE_LOCK so the CPU stays awake for processing
 * - Wires JarvisOrchestrator (central brain) so STT→AIEngine→TTS pipeline
 *   runs entirely in the background
 * - Wake word detection triggers the full STT→AI→TTS pipeline
 * - Started on boot via BootReceiver
 */
@AndroidEntryPoint
class AladdinForegroundService : LifecycleService() {

    @Inject lateinit var orchestrator: JarvisOrchestrator
    @Inject lateinit var wakeWordDetector: WakeWordDetector

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isRunning = false

    companion object {
        const val ACTION_START = "com.aladdin.app.action.START_FG_SERVICE"
        const val ACTION_STOP  = "com.aladdin.app.action.STOP_FG_SERVICE"
        const val ACTION_PROCESS_INPUT = "com.aladdin.app.action.PROCESS_INPUT"
        const val EXTRA_USER_INPUT = "user_input"

        fun start(context: Context) {
            val intent = Intent(context, AladdinForegroundService::class.java)
                .setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AladdinForegroundService::class.java).setAction(ACTION_STOP)
            )
        }

        fun processInput(context: Context, input: String) {
            context.startService(
                Intent(context, AladdinForegroundService::class.java)
                    .setAction(ACTION_PROCESS_INPUT)
                    .putExtra(EXTRA_USER_INPUT, input)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        acquireWakeLock()
        observeOrchestratorState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop requested")
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_PROCESS_INPUT -> {
                val input = intent.getStringExtra(EXTRA_USER_INPUT) ?: return START_STICKY
                serviceScope.launch {
                    orchestrator.processUserInput(input)
                }
            }
            else -> {
                if (!isRunning) {
                    isRunning = true
                    startForeground(NOTIFICATION_ID, buildNotification("Listening for \"Aladdin\"…"))
                    bootOrchestrator()
                    Log.d(TAG, "Foreground service started — orchestrator online")
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        serviceScope.cancel()
        orchestrator.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    // ─── Boot sequence ────────────────────────────────────────────────────────

    private fun bootOrchestrator() {
        serviceScope.launch {
            try {
                orchestrator.start()
                Log.i(TAG, "Orchestrator started — full pipeline active")
            } catch (e: Exception) {
                Log.e(TAG, "Orchestrator start failed: ${e.message}", e)
            }
        }

        // Wire wake-word → orchestrator pipeline
        wakeWordDetector.startListening()
        wakeWordDetector.wakeEvents
            .onEach { event ->
                Log.d(TAG, "Wake word '${event.keyword}' detected (conf=${event.confidence})")
                updateNotification("Listening…")
                orchestrator.onWakeWordDetected(event)
            }
            .launchIn(serviceScope)
    }

    // ─── Orchestrator state observer ──────────────────────────────────────────

    private fun observeOrchestratorState() {
        orchestrator.statusFlow
            .onEach { status -> updateNotification(status) }
            .launchIn(lifecycleScope)
    }

    // ─── Wake lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Log.d(TAG, "Wake lock released")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(statusText: String = "Listening…"): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setContentTitle("Aladdin")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_delete,
                getString(com.aladdin.app.R.string.notif_action_stop),
                stopIntent
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        if (!isRunning) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun shutdown() {
        isRunning = false
        wakeWordDetector.stopListening()
        serviceScope.cancel()
        orchestrator.shutdown()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
