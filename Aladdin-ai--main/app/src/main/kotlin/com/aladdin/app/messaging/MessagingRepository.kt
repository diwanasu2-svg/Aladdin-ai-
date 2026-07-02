package com.aladdin.app.messaging

import android.util.Log
import com.aladdin.app.messaging.discord.DiscordBot
import com.aladdin.app.messaging.email.EmailService
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.OutgoingMessage
import com.aladdin.app.messaging.models.Platform
import com.aladdin.app.messaging.models.SendResult
import com.aladdin.app.messaging.telegram.TelegramBot
import com.aladdin.app.messaging.whatsapp.WhatsAppBot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessagingRepository"

/**
 * MessagingRepository — unified facade over all messaging platforms.
 *
 * Merge all platform [incomingMessages] flows into a single [allMessages] flow
 * that the AI engine / conversation manager subscribes to.
 *
 * Send via [send] with a platform-agnostic [OutgoingMessage].
 */
@Singleton
class MessagingRepository @Inject constructor(
    private val telegramBot: TelegramBot,
    private val whatsAppBot: WhatsAppBot,
    private val discordBot: DiscordBot,
    private val emailService: EmailService,
    private val config: MessagingConfig
) {
    /** Merged stream of all inbound messages from every connected platform. */
    val allMessages: SharedFlow<Message> get() = _allMessages
    private val _allMessages = MutableSharedFlow<Message>(extraBufferCapacity = 128)

    init {
        // Re-emit from each platform flow into the unified flow
        // (Caller must collect within a CoroutineScope, e.g. MessagingService)
    }

    // Platforms forward their flows to us here
    suspend fun startMerging() {
        merge(
            telegramBot.incomingMessages,
            whatsAppBot.incomingMessages,
            discordBot.incomingMessages,
            emailService.incomingMessages
        ).collect { msg ->
            Log.d(TAG, "[${msg.platform}] ${msg.senderName}: ${msg.text.take(80)}")
            _allMessages.emit(msg)
        }
    }

    /** Route an inbound message from FCM or webhook to the unified stream. */
    suspend fun onIncomingMessage(message: Message) {
        _allMessages.emit(message)
    }

    // ─── Send ─────────────────────────────────────────────────────────────────

    suspend fun send(msg: OutgoingMessage): SendResult {
        Log.d(TAG, "Sending to ${msg.platform}: ${msg.text?.take(60)}")
        return when (msg.platform) {
            Platform.TELEGRAM -> {
                if (msg.voiceFilePath != null) {
                    telegramBot.sendVoiceReply(msg.chatId, msg.text ?: "")
                } else {
                    telegramBot.sendText(msg.chatId, msg.text ?: "")
                }
            }
            Platform.WHATSAPP -> {
                whatsAppBot.sendText(msg.chatId, msg.text ?: "")
            }
            Platform.DISCORD -> {
                if (msg.replyToId != null) {
                    discordBot.sendReply(msg.chatId, msg.replyToId, msg.text ?: "")
                } else {
                    discordBot.sendText(msg.chatId, msg.text ?: "")
                }
            }
            Platform.EMAIL -> {
                val lines = msg.text?.lines() ?: listOf("")
                val subject = lines.firstOrNull() ?: "Aladdin Reply"
                val body = lines.drop(1).joinToString("\n")
                emailService.sendViaGmailApi(msg.chatId, subject, body)
            }
            Platform.FCM -> SendResult.Failure("FCM send not supported from device")
        }
    }

    // ─── Polling triggers (called by MessagingService) ─────────────────────────

    suspend fun pollTelegram()        = telegramBot.fetchUpdates()
    suspend fun pollDiscord()         = discordBot.fetchNewMessages()
    suspend fun pollEmail()           = emailService.fetchInbox()
}
