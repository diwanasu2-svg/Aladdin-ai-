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
 * Phase 9 — Discord Tool
 * Send messages, list servers/channels, fetch messages, react via Discord REST API.
 */
@Singleton
class DiscordTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "discord"

    override val name = "discord"
    override val description = "Send Discord messages, manage channels, read notifications, upload files, react"

    private val botToken get() = System.getenv("DISCORD_BOT_TOKEN") ?: ""
    private val apiBase = "https://discord.com/api/v10"
    private val httpClient = OkHttpClient()

    private suspend fun apiCall(method: String, path: String, payload: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (botToken.isEmpty()) throw RuntimeException("DISCORD_BOT_TOKEN not set")
            val builder = Request.Builder()
                .url("$apiBase$path")
                .header("Authorization", "Bot $botToken")
                .header("Content-Type", "application/json")
            when (method) {
                "POST" -> builder.post((payload ?: JSONObject()).toString()
                    .toRequestBody("application/json".toMediaType()))
                "PUT" -> builder.put((payload ?: JSONObject()).toString()
                    .toRequestBody("application/json".toMediaType()))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            val resp = httpClient.newCall(builder.build()).execute()
            try { JSONObject(resp.body?.string() ?: "{}") } catch (e: Exception) { JSONObject() }
        }

    suspend fun sendMessage(channelId: String, content: String, replyToId: String? = null): ToolResult {
        return try {
            val payload = JSONObject().apply {
                put("content", content)
                replyToId?.let { put("message_reference", JSONObject().put("message_id", it)) }
            }
            val result = apiCall("POST", "/channels/$channelId/messages", payload)
            ToolResult.success(id, JSONObject().apply {
                put("message_id", result.optString("id"))
                put("channel_id", channelId); put("sent", true)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Discord send error: ${e.message}") }
    }

    suspend fun getMessages(channelId: String, limit: Int = 20): ToolResult {
        return try {
            val result = apiCall("GET", "/channels/$channelId/messages?limit=${minOf(limit, 100)}")
            ToolResult.success(id, JSONObject().apply {
                put("raw", result.toString()); put("channel_id", channelId)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Error") }
    }

    suspend fun addReaction(channelId: String, messageId: String, emoji: String): ToolResult {
        return try {
            val encoded = java.net.URLEncoder.encode(emoji, "UTF-8")
            apiCall("PUT", "/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            ToolResult.success(id, JSONObject().apply {
                put("reacted", emoji); put("message_id", messageId)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Reaction error") }
    }

    suspend fun listServers(): ToolResult {
        return try {
            val result = apiCall("GET", "/users/@me/guilds")
            ToolResult.success(id, JSONObject().put("servers", result.toString()).toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Error") }
    }

    fun openDiscord(): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("discord://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().put("discord_opened", true).toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Open failed") }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "send")) {
            "send" -> sendMessage((params["channel_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "channel_id")), (params["content"] ?: return ToolResult.error(id, "Missing required parameter: " + "content")),
                (params["reply_to"] ?: "").ifEmpty { null })
            "messages" -> getMessages((params["channel_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "channel_id")), (params["limit"]?.toIntOrNull() ?: 20))
            "react" -> addReaction((params["channel_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "channel_id")),
                (params["message_id"] ?: return ToolResult.error(id, "Missing required parameter: " + "message_id")), (params["emoji"] ?: return ToolResult.error(id, "Missing required parameter: " + "emoji")))
            "servers" -> listServers()
            "open" -> openDiscord()
            else -> ToolResult.error(id, "Unknown Discord action: $action")
        }
    }
}
