package com.aladdin.app.messaging.discord

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
//  Discord REST API v10
//  Base URL: https://discord.com/api/v10/
//  Auth: Authorization: Bot {TOKEN}
// ─────────────────────────────────────────────────────────────────────────────

interface DiscordApi {

    /** Get messages from a text channel (latest [limit] messages). */
    @GET("channels/{channelId}/messages")
    suspend fun getMessages(
        @Path("channelId")   channelId: String,
        @Query("limit")      limit: Int = 50,
        @Query("after")      afterId: String? = null,
        @Header("Authorization") auth: String
    ): Response<List<DiscordMessage>>

    /** Post a message to a text channel. */
    @POST("channels/{channelId}/messages")
    suspend fun sendMessage(
        @Path("channelId")       channelId: String,
        @Body                    body: DiscordSendMessageRequest,
        @Header("Authorization") auth: String
    ): Response<DiscordMessage>

    /** Get info about a guild (server). */
    @GET("guilds/{guildId}")
    suspend fun getGuild(
        @Path("guildId")         guildId: String,
        @Header("Authorization") auth: String
    ): Response<DiscordGuild>

    /** List voice channels in a guild. */
    @GET("guilds/{guildId}/channels")
    suspend fun getChannels(
        @Path("guildId")         guildId: String,
        @Header("Authorization") auth: String
    ): Response<List<DiscordChannel>>

    /** Get the bot user info. */
    @GET("users/@me")
    suspend fun getMe(
        @Header("Authorization") auth: String
    ): Response<DiscordUser>
}

// ─── Models ───────────────────────────────────────────────────────────────────

data class DiscordMessage(
    @SerializedName("id")              val id: String,
    @SerializedName("channel_id")      val channelId: String,
    @SerializedName("author")          val author: DiscordUser,
    @SerializedName("content")         val content: String,
    @SerializedName("timestamp")       val timestamp: String,
    @SerializedName("referenced_message") val referencedMessage: DiscordMessage? = null,
    @SerializedName("attachments")     val attachments: List<DiscordAttachment> = emptyList()
)

data class DiscordUser(
    @SerializedName("id")       val id: String,
    @SerializedName("username") val username: String,
    @SerializedName("bot")      val bot: Boolean = false
)

data class DiscordGuild(
    @SerializedName("id")   val id: String,
    @SerializedName("name") val name: String
)

data class DiscordChannel(
    @SerializedName("id")   val id: String,
    @SerializedName("type") val type: Int,   // 0=text, 2=voice, 4=category
    @SerializedName("name") val name: String
)

data class DiscordAttachment(
    @SerializedName("id")          val id: String,
    @SerializedName("filename")    val filename: String,
    @SerializedName("url")         val url: String,
    @SerializedName("content_type") val contentType: String? = null
)

// ─── Request bodies ───────────────────────────────────────────────────────────

data class DiscordSendMessageRequest(
    @SerializedName("content")           val content: String,
    @SerializedName("message_reference") val messageReference: DiscordMessageReference? = null
)

data class DiscordMessageReference(
    @SerializedName("message_id") val messageId: String
)
