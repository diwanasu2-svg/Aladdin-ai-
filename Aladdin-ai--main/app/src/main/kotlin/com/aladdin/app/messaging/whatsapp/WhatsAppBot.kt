package com.aladdin.app.messaging.whatsapp

import android.util.Log
import com.aladdin.app.messaging.MessagingConfig
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.Platform
import com.aladdin.app.messaging.models.SendResult
import com.aladdin.app.messaging.voice.VoiceReplyHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WhatsAppBot"

/**
 * WhatsAppBot — sends/receives messages via the WhatsApp Business Cloud API.
 *
 * Receiving messages:
 *   Webhook payloads are forwarded from your backend → call [processWebhookPayload(json)]
 *   on every POST to your /webhook endpoint.
 *
 * Sending messages:
 *   [sendText]  — plain text message
 *   [sendVoiceReply] — synthesize TTS and send as WhatsApp audio
 *   [sendMedia] — image or document by public URL
 */
@Singleton
class WhatsAppBot @Inject constructor(
    private val api: WhatsAppApi,
    private val config: MessagingConfig,
    private val voiceHelper: VoiceReplyHelper
) {
    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Message> = _incomingMessages

    private val gson = Gson()
    private val authHeader get() = "Bearer ${config.whatsappAccessToken}"

    // ─── Send text ────────────────────────────────────────────────────────────

    suspend fun sendText(to: String, text: String): SendResult {
        if (config.whatsappAccessToken.isBlank()) return SendResult.Failure("WhatsApp not configured")
        return try {
            val body = WaTextMessageRequest(to = to, text = WaText(body = text))
            val resp = api.sendTextMessage(config.whatsappPhoneNumberId, body, authHeader)
            if (resp.isSuccessful) {
                val msgId = resp.body()?.messages?.firstOrNull()?.id ?: ""
                SendResult.Success(msgId)
            } else {
                SendResult.Failure("HTTP ${resp.code()}: ${resp.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendText error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        }
    }

    // ─── Send voice ───────────────────────────────────────────────────────────

    suspend fun sendVoiceReply(to: String, text: String): SendResult {
        val audioFile = voiceHelper.synthesizeToFile(text) ?: return SendResult.Failure("TTS failed")
        // WhatsApp requires a publicly hosted audio URL for Cloud API.
        // Upload the file to your backend and pass the returned media ID / URL.
        Log.d(TAG, "Voice file ready: ${audioFile.absolutePath} — upload to your backend first")
        return SendResult.Failure("Upload audio to your backend and pass the media_id")
    }

    // ─── Send media (image / document) ───────────────────────────────────────

    suspend fun sendMedia(to: String, mediaUrl: String, type: String = "image", caption: String? = null): SendResult {
        if (config.whatsappAccessToken.isBlank()) return SendResult.Failure("WhatsApp not configured")
        return try {
            val media = WaMedia(link = mediaUrl, caption = caption)
            val body = WaMediaMessageRequest(
                to = to, type = type,
                image = if (type == "image") media else null,
                document = if (type == "document") media else null
            )
            val resp = api.sendMediaMessage(config.whatsappPhoneNumberId, body, authHeader)
            if (resp.isSuccessful) SendResult.Success(resp.body()?.messages?.firstOrNull()?.id ?: "")
            else SendResult.Failure("HTTP ${resp.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "sendMedia error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        }
    }

    // ─── Webhook inbound ──────────────────────────────────────────────────────

    fun processWebhookPayload(json: String) {
        try {
            val payload = gson.fromJson(json, WaWebhookPayload::class.java)
            payload.entry.forEach { entry ->
                entry.changes.forEach { change ->
                    change.value.messages?.forEach { msg ->
                        val contact = change.value.contacts?.find { it.waId == msg.from }
                        val inbound = Message(
                            id = msg.id,
                            platform = Platform.WHATSAPP,
                            chatId = msg.from,
                            senderId = msg.from,
                            senderName = contact?.profile?.name ?: msg.from,
                            text = msg.text?.body ?: "[${msg.type}]"
                        )
                        _incomingMessages.tryEmit(inbound)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processWebhookPayload error", e)
        }
    }
}
