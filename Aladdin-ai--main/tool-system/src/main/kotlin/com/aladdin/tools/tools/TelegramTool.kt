package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/**
 * Phase 9 — Telegram Tool
 * Send/receive messages, forward, share files, get chat info via Telegram Bot API.
 */
@Singleton
class TelegramTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "telegram"

    override val name = "telegram"
    override val description = "Send/receive Telegram messages, forward messages, share media, manage chats"

    private val botToken get() = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
    private val apiBase get() = "https://api.telegram.org/bot$botToken"
    private val httpClient = OkHttpClient()

    private suspend fun apiCall(method: String, payload: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            if (botToken.isEmpty()) throw RuntimeException("TELEGRAM_BOT_TOKEN not set")
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$apiBase/$method").post(body).build()
            val resp = httpClient.newCall(req).execute()
            JSONObject(resp.body?.string() ?: "{}")
        }

    suspend fun sendMessage(chatId: String, text: String, parseMode: String = ""): ToolResult {
        return try {
            val payload = JSONObject().apply {
                put("chat_id", chatId); put("text", text)
                if (parseMode.isNotBlank()) put("parse_mode", parseMode)
            }
            val result = apiCall("sendMessage", payload)
            if (result.optBoolean("ok", false)) {
                val msg = result.getJSONObject("result")
                ToolResult.success(id, JSONObject().apply {
                    put("message_id", msg.getLong("message_id"))
                    put("chat_id", chatId); put("sent", true)
                }.toString())
            } else {
                ToolResult.error(id, result.optString("description", "Send failed"))
            }
        } catch (e: Exception) { ToolResult.error(id, "Telegram send error: ${e.message}") }
    }

    suspend fun getUpdates(limit: Int = 10, offset: Int = 0): ToolResult {
        return try {
            val payload = JSONObject().apply { put("limit", limit); put("offset", offset) }
            val result = apiCall("getUpdates", payload)
            if (result.optBoolean("ok", false)) {
                val updates = result.getJSONArray("result")
                val messages = mutableListOf<String>()
                for (i in 0 until updates.length()) {
                    val upd = updates.getJSONObject(i)
                    val msg = upd.optJSONObject("message") ?: continue
                    messages.add(JSONObject().apply {
                        put("update_id", upd.getLong("update_id"))
                        put("chat_id", msg.getJSONObject("chat").getLong("id"))
                        put("text", msg.optString("text"))
                        put("from", msg.optJSONObject("from")?.optString("username") ?: "")
                    }.toString())
                }
                ToolResult.success(id, JSONObject().apply {
                    put("messages", messages); put("count", messages.size)
                }.toString())
            } else { ToolResult.error(id, "getUpdates failed") }
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Error") }
    }

    suspend fun forwardMessage(fromChatId: String, toChatId: String, messageId: Long): ToolResult {
        return try {
            val payload = JSONObject().apply {
                put("from_chat_id", fromChatId); put("chat_id", toChatId)
                put("message_id", messageId)
            }
            val result = apiCall("forwardMessage", payload)
            ToolResult.success(id, JSONObject().put("forwarded", result.optBoolean("ok", false)).toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Forward failed") }
    }

    // Open Telegram chat via intent
    fun openChat(username: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username")).apply {
                setPackage("org.telegram.messenger")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().put("opened_chat", username).toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Open chat failed") }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "send")) {
            "send" -> sendMessage((params["chat_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "chat_id")), (params["text"] ?: return ToolResult.error(id, "Missing required parameter: " + "text")),
                (params["parse_mode"] ?: ""))
            "updates" -> getUpdates((params["limit"]?.toIntOrNull() ?: 10), (params["offset"]?.toIntOrNull() ?: 0))
            "forward" -> forwardMessage((params["from_chat_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "from_chat_id")),
                (params["to_chat_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "to_chat_id")), (params["message_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing required parameter: " + "message_id")))
            "open" -> openChat((params["username"] ?: return ToolResult.error(id, "Missing required parameter: " + "username")))
            else -> ToolResult.error(id, "Unknown Telegram action: $action")
        }
    }
}
