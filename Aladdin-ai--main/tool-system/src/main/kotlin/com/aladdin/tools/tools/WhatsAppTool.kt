package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aladdin.tools.tools.BaseTool
import org.json.JSONObject

/**
 * Phase 9 — WhatsApp Tool
 * Send messages and media via WhatsApp Android intent. For full API access configure Twilio WhatsApp.
 */
@Singleton
class WhatsAppTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "whatsapp"

    override val name = "whatsapp"
    override val description = "Send WhatsApp messages and media, search chats, generate reply suggestions"

    fun sendMessage(number: String, message: String): ToolResult {
        return try {
            val clean = number.replace(Regex("[^+0-9]"), "")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$clean&text=${Uri.encode(message)}")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().apply {
                put("sent_via", "whatsapp_intent"); put("to", number)
                put("message_preview", message.take(80))
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "WhatsApp not installed or error: ${e.message}")
        }
    }

    fun shareMedia(uri: Uri, mimeType: String, caption: String = ""): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().apply {
                put("media_shared", true); put("mime_type", mimeType)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Media share error: ${e.message}")
        }
    }

    fun generateReplySuggestion(incomingMessage: String, tone: String = "friendly"): ToolResult {
        val msg = incomingMessage.lowercase()
        val suggestions = when {
            msg.contains("hello") || msg.contains("hi") || msg.contains("hey") ->
                listOf("Hey! How are you?", "Hi there! 😊", "Hello! Great to hear from you.")
            msg.contains("thank") ->
                listOf("You're welcome!", "Happy to help!", "Anytime! 🙌")
            msg.contains("?") ->
                listOf("I'll get back to you on that.", "Let me check and confirm.", "Sure, one moment!")
            msg.contains("ok") || msg.contains("sure") ->
                listOf("Great!", "Perfect!", "Sounds good!")
            else -> listOf("Got it!", "Understood, thanks.", "I'll look into it.")
        }
        return ToolResult.success(id, JSONObject().apply {
            put("suggestions", suggestions.toString())
            put("original", incomingMessage)
            put("tone", tone)
        }.toString())
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "send")) {
            "send" -> sendMessage((params["to"] ?: return ToolResult.error(id, "Missing required parameter: " + "to")), (params["message"] ?: return ToolResult.error(id, "Missing required parameter: " + "message")))
            "reply_suggestion" -> generateReplySuggestion(
                (params["message"] ?: return ToolResult.error(id, "Missing required parameter: " + "message")), (params["tone"] ?: "friendly"))
            else -> ToolResult.error(id, "Unknown WhatsApp action: $action")
        }
    }
}
