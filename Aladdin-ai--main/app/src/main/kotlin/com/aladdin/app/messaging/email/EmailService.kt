package com.aladdin.app.messaging.email

import android.util.Base64
import android.util.Log
import com.aladdin.app.messaging.MessagingConfig
import com.aladdin.app.messaging.models.Message
import com.aladdin.app.messaging.models.Platform
import com.aladdin.app.messaging.models.SendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

private const val TAG = "EmailService"

/**
 * EmailService — two strategies for email:
 *
 *  1. **Gmail REST API** (preferred) — [fetchInbox], [sendViaGmailApi]
 *     Requires OAuth2 access token in [MessagingConfig.gmailAccessToken].
 *
 *  2. **JavaMail / SMTP** (fallback) — [sendViaSMTP]
 *     Requires SMTP credentials in [MessagingConfig].
 *
 * Incoming messages are emitted on [incomingMessages] for the AI engine to process.
 */
@Singleton
class EmailService @Inject constructor(
    private val gmailApi: GmailApi,
    private val config: MessagingConfig
) {
    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Message> = _incomingMessages

    private val authHeader get() = "Bearer ${config.gmailAccessToken}"
    private val processedIds = mutableSetOf<String>()

    // ─── Fetch inbox (Gmail REST) ─────────────────────────────────────────────

    suspend fun fetchInbox(query: String = "in:inbox is:unread") {
        if (config.gmailAccessToken.isBlank()) return
        try {
            val listResp = gmailApi.listMessages(query = query, auth = authHeader)
            val refs = listResp.body()?.messages ?: return
            for (ref in refs) {
                if (ref.id in processedIds) continue
                val msgResp = gmailApi.getMessage(messageId = ref.id, auth = authHeader)
                val msg = msgResp.body() ?: continue
                processedIds.add(ref.id)
                markAsRead(ref.id)

                val headers = msg.payload?.headers ?: emptyList()
                val from    = headers.find { it.name == "From" }?.value ?: "unknown"
                val subject = headers.find { it.name == "Subject" }?.value ?: "(no subject)"
                val body    = extractBody(msg.payload)

                val inbound = Message(
                    id = ref.id,
                    platform = Platform.EMAIL,
                    chatId = ref.threadId,
                    senderId = from,
                    senderName = from,
                    text = "Subject: $subject\n\n$body"
                )
                _incomingMessages.tryEmit(inbound)
                Log.d(TAG, "Email received from $from: $subject")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchInbox error", e)
        }
    }

    // ─── Send via Gmail REST ──────────────────────────────────────────────────

    suspend fun sendViaGmailApi(to: String, subject: String, body: String): SendResult {
        if (config.gmailAccessToken.isBlank()) return SendResult.Failure("Gmail not configured")
        return try {
            val raw = buildMimeMessage(
                from    = config.emailFromAddress,
                to      = to,
                subject = subject,
                body    = body
            )
            val resp = gmailApi.sendMessage(body = GmailSendRequest(raw = raw), auth = authHeader)
            if (resp.isSuccessful) SendResult.Success(resp.body()?.id ?: "")
            else SendResult.Failure("HTTP ${resp.code()}: ${resp.errorBody()?.string()}")
        } catch (e: Exception) {
            Log.e(TAG, "sendViaGmailApi error", e)
            SendResult.Failure(e.message ?: "Exception", e)
        }
    }

    // ─── Send via SMTP (JavaMail) ─────────────────────────────────────────────

    suspend fun sendViaSMTP(to: String, subject: String, body: String): SendResult {
        if (config.smtpUser.isBlank()) return SendResult.Failure("SMTP not configured")
        return withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", config.smtpHost)
                    put("mail.smtp.port", config.smtpPort.toString())
                    put("mail.smtp.ssl.trust", config.smtpHost)
                }
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(config.smtpUser, config.smtpPassword)
                })
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(config.smtpUser))
                    setRecipients(javax.mail.Message.RecipientType.TO, InternetAddress.parse(to))
                    this.subject = subject
                    setText(body)
                }
                Transport.send(msg)
                Log.d(TAG, "Email sent via SMTP to $to")
                SendResult.Success("smtp_ok")
            } catch (e: Exception) {
                Log.e(TAG, "sendViaSMTP error", e)
                SendResult.Failure(e.message ?: "SMTP Exception", e)
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun markAsRead(messageId: String) {
        try {
            gmailApi.modifyMessage(
                messageId = messageId,
                body = GmailModifyRequest(removeLabelIds = listOf("UNREAD")),
                auth = authHeader
            )
        } catch (_: Exception) {}
    }

    private fun extractBody(payload: GmailPayload?): String {
        if (payload == null) return ""
        // Try direct body first
        payload.body?.data?.let { return decodeBase64(it) }
        // Recurse into multipart
        payload.parts?.forEach { part ->
            if (part.mimeType == "text/plain") {
                part.body?.data?.let { return decodeBase64(it) }
            }
        }
        return ""
    }

    private fun decodeBase64(encoded: String): String = try {
        String(Base64.decode(encoded.replace('-', '+').replace('_', '/'), Base64.DEFAULT))
    } catch (_: Exception) { "" }

    /**
     * Build a base64url-encoded RFC 2822 message for the Gmail API.
     */
    private fun buildMimeMessage(from: String, to: String, subject: String, body: String): String {
        val raw = "From: $from\r\nTo: $to\r\nSubject: $subject\r\n\r\n$body"
        return Base64.encodeToString(raw.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
