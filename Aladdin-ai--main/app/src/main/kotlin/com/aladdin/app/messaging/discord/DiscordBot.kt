package com.aladdin.app.messaging.discord

import android.util.Log
import com.aladdin.app.messaging.MessagingConfig
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.Platform
import com.aladdin.app.messaging.models.SendResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscordBot"

/**
 * DiscordBot — polls a Discord text channel for new messages and sends replies.
 *
 * Discord doesn't support long-polling from mobile clients natively (no Gateway
 * WebSocket from Android without a JVM library). This implementation uses REST
 * polling: call [fetchNewMessages] on a schedule (e.g., every 10 s).
 *
 * Features:
 *  - [fetchNewMessages]  — polls channel, emits new [Message]s
 *  - [sendText]          — post a text reply to a channel
 *  - [sendReply]         — reply to a specific message
 *  - [listVoiceChannels] — enumerate voice channels in a guild
 */
@Singleton
class DiscordBot @Inject constructor(
    private val api: DiscordApi,
    private val config: MessagingConfig
) {
    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Message> = _incomingMessages

    private val authHeader get() = "Bot ${config.discordBotToken}"

    /** Id of the last processed message — used as cursor for polling. */
    private var lastMessageId: String? = null
    private var botUserId: String? = null

    // ─── Polling ──────────────────────────────────────────────────────────────

    suspend fun fetchNewMessages(channelId: String = config.discordDefaultChannelId) {
        if (config.discordBotToken.isBlank() || channelId.isBlank()) return
        ensureBotId()
        try {
            val resp = api.getMessages(channelId, limit = 50, afterId = lastMessageId, auth = authHeader)
            val messages = resp.body() ?: return
            // Discord returns newest-first when using `after`; sort oldest-first
            messages.sortedBy { it.id }.forEach { msg ->
                if (msg.author.bot && msg.author.id == botUserId) return@forEach  // skip self
                lastMessageId = msg.id
                _incomingMessages.tryEmit(msg.toUnified())
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchNewMessages error", e)
        }
    }

    // ─── Send ─────────────────────────────────────────────────────────────────

    suspend fun sendText(channelId: String, text: String): SendResult {
        if (config.discordBotToken.isBlank()) return SendResult.Failure("Discord not configured")
        return try {
            val resp = api.sendMessage(channelId, DiscordSendMessageRequest(content = text), authHeader)
            if (resp.isSuccessful) SendResult.Success(resp.body()?.id ?: "")
            else SendResult.Failure("HTTP ${resp.code()}: ${resp.errorBody()?.string()}")
        } catch (e: Exception) {
            Log.e(TAG, "sendText error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        }
    }

    suspend fun sendReply(channelId: String, replyToMessageId: String, text: String): SendResult {
        if (config.discordBotToken.isBlank()) return SendResult.Failure("Discord not configured")
        return try {
            val body = DiscordSendMessageRequest(
                content = text,
                messageReference = DiscordMessageReference(messageId = replyToMessageId)
            )
            val resp = api.sendMessage(channelId, body, authHeader)
            if (resp.isSuccessful) SendResult.Success(resp.body()?.id ?: "")
            else SendResult.Failure("HTTP ${resp.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "sendReply error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        }
    }

    // ─── Voice channels ───────────────────────────────────────────────────────

    suspend fun listVoiceChannels(guildId: String): List<DiscordChannel> {
        return try {
            val resp = api.getChannels(guildId, authHeader)
            resp.body()?.filter { it.type == 2 } ?: emptyList()  // type 2 = voice
        } catch (e: Exception) {
            Log.e(TAG, "listVoiceChannels error", e)
            emptyList()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun ensureBotId() {
        if (botUserId != null) return
        try {
            botUserId = api.getMe(authHeader).body()?.id
        } catch (e: Exception) {
            Log.e(TAG, "ensureBotId error", e)
        }
    }

    private fun DiscordMessage.toUnified() = Message(
        id = id,
        platform = Platform.DISCORD,
        chatId = channelId,
        senderId = author.id,
        senderName = author.username,
        text = content
    )
}
