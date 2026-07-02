package com.aladdin.app.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

private const val TAG = "AladdinBgService"

/**
 * AladdinBackgroundService
 *
 * Handles non-foreground processing tasks:
 * - Network calls (sync, API polling)
 * - Data processing pipelines
 * - Periodic background jobs that don't need an ongoing notification
 *
 * This service is started by [AladdinForegroundService] or [BootReceiver].
 * For longer-lived work, prefer WorkManager — this service is a lightweight
 * coordinator.
 *
 * Note: On Android 8+ a background service started from the background will be
 * killed after ~1 minute unless the app is in the foreground or whitelisted.
 * Use the foreground service or WorkManager for persistent work.
 */
@AndroidEntryPoint
class AladdinBackgroundService : LifecycleService() {

    @Inject lateinit var aiEngine: com.aladdin.engine.engine.AIEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_SYNC    = "com.aladdin.app.action.BACKGROUND_SYNC"
        const val ACTION_PROCESS = "com.aladdin.app.action.BACKGROUND_PROCESS"
        const val EXTRA_PAYLOAD  = "payload"

        fun startSync(context: Context) {
            context.startService(
                Intent(context, AladdinBackgroundService::class.java).setAction(ACTION_SYNC)
            )
        }

        fun startProcess(context: Context, payload: String) {
            context.startService(
                Intent(context, AladdinBackgroundService::class.java)
                    .setAction(ACTION_PROCESS)
                    .putExtra(EXTRA_PAYLOAD, payload)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SYNC    -> handleSync()
            ACTION_PROCESS -> handleProcess(intent.getStringExtra(EXTRA_PAYLOAD) ?: "")
            else           -> handleSync()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Task handlers ────────────────────────────────────────────────────────

    private fun handleSync() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Sync started")
                // Flush conversation context and run background goal-tracker maintenance
                if (::aiEngine.isInitialized) {
                    aiEngine.flushContext()
                    aiEngine.runBackgroundMaintenance()
                }
                Log.d(TAG, "Sync complete")
            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
            } finally {
                stopSelfWhenIdle()
            }
        }
    }

    private fun handleProcess(payload: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Processing: $payload")
                // Route the payload through the AI engine (deferred tasks, background queries)
                if (payload.isNotBlank() && ::aiEngine.isInitialized) {
                    aiEngine.process(payload)
                }
                Log.d(TAG, "Processing complete")
            } catch (e: Exception) {
                Log.e(TAG, "Processing error", e)
            } finally {
                stopSelfWhenIdle()
            }
        }
    }

    private fun stopSelfWhenIdle() {
        val running = serviceScope.coroutineContext[Job]?.children?.count() ?: 0
        if (running == 0) stopSelf()
    }
}
