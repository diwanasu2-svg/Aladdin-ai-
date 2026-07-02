package com.aladdin.app.messaging.telegram

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
//  Telegram Bot REST API  (https://core.telegram.org/bots/api)
//  Base URL: https://api.telegram.org/bot{TOKEN}/
// ─────────────────────────────────────────────────────────────────────────────

interface TelegramApi {

    /** Returns the list of pending updates (long-poll). */
    @GET("getUpdates")
    suspend fun getUpdates(
        @Query("offset")          offset: Long = 0,
        @Query("limit")           limit: Int = 100,
        @Query("timeout")         timeout: Int = 0,
        @Query("allowed_updates") allowedUpdates: List<String> = listOf("message", "callback_query")
    ): Response<TelegramResponse<List<TelegramUpdate>>>

    /** Send a plain-text message. */
    @POST("sendMessage")
    suspend fun sendMessage(@Body body: SendMessageRequest): Response<TelegramResponse<TelegramMessage>>

    /** Send a voice message (OGG/OPUS audio). */
    @Multipart
    @POST("sendVoice")
    suspend fun sendVoice(
        @Part("chat_id") chatId: RequestBody,
        @Part voice: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null
    ): Response<TelegramResponse<TelegramMessage>>

    /** Send a photo. */
    @Multipart
    @POST("sendPhoto")
    suspend fun sendPhoto(
        @Part("chat_id") chatId: RequestBody,
        @Part photo: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null
    ): Response<TelegramResponse<TelegramMessage>>

    /** Get bot info. */
    @GET("getMe")
    suspend fun getMe(): Response<TelegramResponse<TelegramUser>>
}

// ─── Response wrappers ────────────────────────────────────────────────────────

data class TelegramResponse<T>(
    @SerializedName("ok")          val ok: Boolean,
    @SerializedName("result")      val result: T?,
    @SerializedName("description") val description: String? = null
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long,
    @SerializedName("message")   val message: TelegramMessage? = null,
    @SerializedName("callback_query") val callbackQuery: TelegramCallbackQuery? = null
)

data class TelegramMessage(
    @SerializedName("message_id") val messageId: Long,
    @SerializedName("from")       val from: TelegramUser? = null,
    @SerializedName("chat")       val chat: TelegramChat,
    @SerializedName("text")       val text: String? = null,
    @SerializedName("voice")      val voice: TelegramVoice? = null,
    @SerializedName("photo")      val photo: List<TelegramPhotoSize>? = null,
    @SerializedName("date")       val date: Long = 0
)

data class TelegramUser(
    @SerializedName("id")         val id: Long,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("username")   val username: String? = null
)

data class TelegramChat(
    @SerializedName("id")   val id: Long,
    @SerializedName("type") val type: String
)

data class TelegramVoice(
    @SerializedName("file_id")   val fileId: String,
    @SerializedName("duration")  val duration: Int,
    @SerializedName("mime_type") val mimeType: String? = null
)

data class TelegramPhotoSize(
    @SerializedName("file_id")   val fileId: String,
    @SerializedName("width")     val width: Int,
    @SerializedName("height")    val height: Int
)

data class TelegramCallbackQuery(
    @SerializedName("id")      val id: String,
    @SerializedName("from")    val from: TelegramUser,
    @SerializedName("message") val message: TelegramMessage? = null,
    @SerializedName("data")    val data: String? = null
)

// ─── Request bodies ───────────────────────────────────────────────────────────

data class SendMessageRequest(
    @SerializedName("chat_id")    val chatId: String,
    @SerializedName("text")       val text: String,
    @SerializedName("parse_mode") val parseMode: String = "HTML",
    @SerializedName("reply_to_message_id") val replyToMessageId: Long? = null
)
