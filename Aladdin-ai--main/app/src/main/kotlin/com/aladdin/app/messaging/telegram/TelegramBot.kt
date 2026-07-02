package com.aladdin.app.messaging.telegram

import android.util.Log
import com.aladdin.app.messaging.MessagingConfig
import com.aladdin.app.messaging.models.MediaType
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.Platform
import com.aladdin.app.messaging.models.SendResult
import com.aladdin.app.messaging.voice.VoiceReplyHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TelegramBot"

/**
 * TelegramBot — wraps [TelegramApi] with:
 *  - Long-poll update fetching (call [fetchUpdates] in a loop)
 *  - Command processing (/start, /help, /ask <query>)
 *  - Voice message replies via [VoiceReplyHelper]
 *  - Incoming messages emitted on [incomingMessages] SharedFlow
 */
@Singleton
class TelegramBot @Inject constructor(
    private val api: TelegramApi,
    private val config: MessagingConfig,
    private val voiceHelper: VoiceReplyHelper
) {
    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Message> = _incomingMessages

    private var lastUpdateId: Long = 0L

    // ─── Polling ──────────────────────────────────────────────────────────────

    /** Call in a coroutine loop. Emits new messages on [incomingMessages]. */
    suspend fun fetchUpdates() {
        if (config.telegramBotToken.isBlank()) return
        try {
            val response = api.getUpdates(offset = lastUpdateId + 1, timeout = 0)
            val updates = response.body()?.result ?: return
            for (update in updates) {
                lastUpdateId = maxOf(lastUpdateId, update.updateId)
                processUpdate(update)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUpdates error", e)
        }
    }

    // ─── Send text ────────────────────────────────────────────────────────────

    suspend fun sendText(chatId: String, text: String, replyToId: Long? = null): SendResult {
        return try {
            val body = SendMessageRequest(chatId = chatId, text = text, replyToMessageId = replyToId)
            val resp = api.sendMessage(body)
            if (resp.isSuccessful && resp.body()?.ok == true) {
                SendResult.Success(resp.body()?.result?.messageId?.toString() ?: "")
            } else {
                SendResult.Failure(resp.body()?.description ?: "Unknown error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendText error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        }
    }

    // ─── Send voice reply ─────────────────────────────────────────────────────

    suspend fun sendVoiceReply(chatId: String, text: String): SendResult {
        val audioFile = voiceHelper.synthesizeToFile(text) ?: return SendResult.Failure("TTS failed")
        return try {
            val part = MultipartBody.Part.createFormData(
                "voice", audioFile.name,
                audioFile.asRequestBody("audio/ogg".toMediaTypeOrNull())
            )
            val chatPart = chatId.toRequestBody("text/plain".toMediaTypeOrNull())
            val resp = api.sendVoice(chatPart, part)
            if (resp.isSuccessful && resp.body()?.ok == true) {
                SendResult.Success(resp.body()?.result?.messageId?.toString() ?: "")
            } else {
                SendResult.Failure(resp.body()?.description ?: "Voice send failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendVoiceReply error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        } finally {
            audioFile.delete()
        }
    }

    // ─── Command processing ───────────────────────────────────────────────────

    private suspend fun processUpdate(update: TelegramUpdate) {
        val msg = update.message ?: return
        val chatId = msg.chat.id.toString()
        val text = msg.text ?: return

        val inbound = Message(
            id = msg.messageId.toString(),
            platform = Platform.TELEGRAM,
            chatId = chatId,
            senderId = msg.from?.id?.toString() ?: "unknown",
            senderName = msg.from?.firstName ?: "User",
            text = text
        )
        _incomingMessages.tryEmit(inbound)

        // ─── Built-in commands ────────────────────────────────────────────────
        when {
            text.startsWith("/start") ->
                sendText(chatId, "👋 Hello! I'm <b>Aladdin</b>, your AI assistant.\nSend me a message or use /ask <i>question</i> to chat.")
            text.startsWith("/help") ->
                sendText(chatId, "/ask &lt;query&gt; — Ask Aladdin anything\n/voice &lt;text&gt; — Get a voice reply\n/status — Check bot status")
            text.startsWith("/voice ") -> {
                val query = text.removePrefix("/voice ").trim()
                sendVoiceReply(chatId, query)
            }
            text.startsWith("/status") ->
                sendText(chatId, "✅ Aladdin is online and listening.")
            text.startsWith("/ask ") -> {
                val query = text.removePrefix("/ask ").trim()
                // Emit for MessagingRepository to route to AI engine
                _incomingMessages.tryEmit(inbound.copy(text = query))
            }
        }
    }
}
