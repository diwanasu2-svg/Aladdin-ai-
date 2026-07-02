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
class DiscordTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool() {

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
            ToolResult.success(JSONObject().apply {
                put("message_id", result.optString("id"))
                put("channel_id", channelId); put("sent", true)
            })
        } catch (e: Exception) { ToolResult.error("Discord send error: ${e.message}") }
    }

    suspend fun getMessages(channelId: String, limit: Int = 20): ToolResult {
        return try {
            val result = apiCall("GET", "/channels/$channelId/messages?limit=${minOf(limit, 100)}")
            ToolResult.success(JSONObject().apply {
                put("raw", result.toString()); put("channel_id", channelId)
            })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Error") }
    }

    suspend fun addReaction(channelId: String, messageId: String, emoji: String): ToolResult {
        return try {
            val encoded = java.net.URLEncoder.encode(emoji, "UTF-8")
            apiCall("PUT", "/channels/$channelId/messages/$messageId/reactions/$encoded/@me")
            ToolResult.success(JSONObject().apply {
                put("reacted", emoji); put("message_id", messageId)
            })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Reaction error") }
    }

    suspend fun listServers(): ToolResult {
        return try {
            val result = apiCall("GET", "/users/@me/guilds")
            ToolResult.success(JSONObject().put("servers", result.toString()))
        } catch (e: Exception) { ToolResult.error(e.message ?: "Error") }
    }

    fun openDiscord(): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("discord://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(JSONObject().put("discord_opened", true))
        } catch (e: Exception) { ToolResult.error(e.message ?: "Open failed") }
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        return when (val action = params.optString("action", "send")) {
            "send" -> sendMessage(params.getString("channel_id"), params.getString("content"),
                params.optString("reply_to", "").ifEmpty { null })
            "messages" -> getMessages(params.getString("channel_id"), params.optInt("limit", 20))
            "react" -> addReaction(params.getString("channel_id"),
                params.getString("message_id"), params.getString("emoji"))
            "servers" -> listServers()
            "open" -> openDiscord()
            else -> ToolResult.error("Unknown Discord action: $action")
        }
    }
}
