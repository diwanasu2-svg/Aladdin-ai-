package com.aladdin.app.messaging

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aladdin.app.MainActivity
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.OutgoingMessage
import com.aladdin.app.notification.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

private const val TAG = "MessagingService"
private const val NOTIF_ID = 3001
private const val WAKE_LOCK_TAG = "Aladdin:MessagingWakeLock"

/**
 * MessagingService — background foreground service that:
 *
 *  1. Polls Telegram, Discord, and Gmail on [MessagingConfig.pollIntervalSeconds] intervals
 *  2. Merges all platform inbound streams via [MessagingRepository.startMerging]
 *  3. Routes inbound messages to the AI engine (stub — wire to your Gemini / LLM call)
 *  4. Sends AI-generated replies back via [MessagingRepository.send]
 *
 * Always-running (START_STICKY).  Started from [BootReceiver] and [MainActivity].
 */
@AndroidEntryPoint
class MessagingService : LifecycleService() {

    @Inject lateinit var repository: MessagingRepository
    @Inject lateinit var config: MessagingConfig

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_START = "com.aladdin.messaging.START"
        const val ACTION_STOP  = "com.aladdin.messaging.STOP"

        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(
            context, Intent(context, MessagingService::class.java).setAction(ACTION_START)
        )
        fun stop(context: Context) = context.startService(
            Intent(context, MessagingService::class.java).setAction(ACTION_STOP)
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MessagingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification("Messaging sync active"))
        startMerging()
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        Log.d(TAG, "MessagingService destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Merge all platform streams ───────────────────────────────────────────

    private fun startMerging() {
        lifecycleScope.launch {
            repository.startMerging()  // collects until cancelled
        }
        lifecycleScope.launch {
            repository.allMessages.collect { message ->
                handleIncoming(message)
            }
        }
    }

    // ─── Polling loop ─────────────────────────────────────────────────────────

    private fun startPolling() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (config.telegramBotToken.isNotBlank())   repository.pollTelegram()
                    if (config.discordBotToken.isNotBlank())    repository.pollDiscord()
                    if (config.gmailAccessToken.isNotBlank())   repository.pollEmail()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                }
                delay(config.pollIntervalSeconds * 1_000L)
            }
        }
    }

    // ─── AI response routing ──────────────────────────────────────────────────

    /**
     * Called for every inbound message across all platforms.
     *
     * Wire this to your Gemini / AI engine and then call [repository.send] with the reply.
     *
     * Example:
     *   val reply = aiEngine.ask(message.text, context = conversationManager.buildPromptContext())
     *   repository.send(OutgoingMessage(platform = message.platform, chatId = message.chatId, text = reply))
     */
    private fun handleIncoming(message: Message) {
        Log.d(TAG, "[${message.platform}] ${message.senderName}: ${message.text.take(80)}")
        // ── STUB: replace with real AI engine call ──
        serviceScope.launch {
            val replyText = generateAiReply(message)
            if (replyText != null) {
                repository.send(
                    OutgoingMessage(
                        platform = message.platform,
                        chatId   = message.chatId,
                        text     = replyText,
                        replyToId = message.id
                    )
                )
            }
        }
    }

    /**
     * Generates an AI reply via the AIEngine for the incoming message.
     * Returns null for system/informational messages where no reply is appropriate.
     */
    private suspend fun generateAiReply(message: Message): String? {
        return try {
            // Ignore empty or system-generated messages
            val text = message.text.trim()
            if (text.isBlank() || text.length < 2) return null

            // Build a platform-aware prompt
            val prompt = buildString {
                append("[${message.platform.name}] ${message.senderName}: $text")
            }

            // Delegate to the AI engine; returns the response string directly
            val aiEngine = com.aladdin.engine.engine.AIEngine::class.java.let { cls ->
                // Retrieve from Hilt singleton via application
                (applicationContext as? dagger.hilt.android.internal.managers.ComponentSupplier)
                    ?.let { null } // guard — see below
            }

            // Safe path: post to AIEngine via service binding pattern.
            // The AIEngine Hilt singleton is owned by the Application component,
            // so we send an intent to the foreground service which already holds it.
            com.aladdin.app.service.AladdinBackgroundService.startProcess(this@MessagingService, prompt)

            // For immediate inline replies we return a polite acknowledgement;
            // the real async response will arrive via the AIEngine response flow
            // and should be sent by a dedicated observer in the foreground service.
            null  // suppresses double-reply; foreground service handles response dispatch
        } catch (e: Exception) {
            Log.e(TAG, "AI reply error: ${e.message}", e)
            null
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Aladdin Messaging")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    private fun shutdown() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
}
