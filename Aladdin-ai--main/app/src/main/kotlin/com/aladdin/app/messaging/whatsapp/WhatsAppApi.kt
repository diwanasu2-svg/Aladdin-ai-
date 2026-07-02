package com.aladdin.app.messaging.whatsapp

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
//  WhatsApp Cloud API  (Meta Business Platform)
//  Base URL: https://graph.facebook.com/v19.0/
//  Auth: Authorization: Bearer {ACCESS_TOKEN}
// ─────────────────────────────────────────────────────────────────────────────

interface WhatsAppApi {

    /** Send a text message. */
    @POST("{phoneNumberId}/messages")
    suspend fun sendTextMessage(
        @Path("phoneNumberId") phoneNumberId: String,
        @Body body: WaTextMessageRequest,
        @Header("Authorization") auth: String
    ): Response<WaMessageResponse>

    /** Send a media message (image, audio, video, document). */
    @POST("{phoneNumberId}/messages")
    suspend fun sendMediaMessage(
        @Path("phoneNumberId") phoneNumberId: String,
        @Body body: WaMediaMessageRequest,
        @Header("Authorization") auth: String
    ): Response<WaMessageResponse>

    /** Send a voice message (audio). */
    @POST("{phoneNumberId}/messages")
    suspend fun sendAudioMessage(
        @Path("phoneNumberId") phoneNumberId: String,
        @Body body: WaAudioMessageRequest,
        @Header("Authorization") auth: String
    ): Response<WaMessageResponse>

    /** Mark a message as read. */
    @POST("{phoneNumberId}/messages")
    suspend fun markAsRead(
        @Path("phoneNumberId") phoneNumberId: String,
        @Body body: WaReadStatusRequest,
        @Header("Authorization") auth: String
    ): Response<WaReadStatusResponse>
}

// ─── Request bodies ───────────────────────────────────────────────────────────

data class WaTextMessageRequest(
    @SerializedName("messaging_product") val messagingProduct: String = "whatsapp",
    @SerializedName("recipient_type")    val recipientType: String = "individual",
    @SerializedName("to")                val to: String,
    @SerializedName("type")              val type: String = "text",
    @SerializedName("text")              val text: WaText
)

data class WaText(
    @SerializedName("preview_url") val previewUrl: Boolean = false,
    @SerializedName("body")        val body: String
)

data class WaMediaMessageRequest(
    @SerializedName("messaging_product") val messagingProduct: String = "whatsapp",
    @SerializedName("to")                val to: String,
    @SerializedName("type")              val type: String,        // "image", "video", "document"
    @SerializedName("image")             val image: WaMedia? = null,
    @SerializedName("video")             val video: WaMedia? = null,
    @SerializedName("document")          val document: WaMedia? = null
)

data class WaAudioMessageRequest(
    @SerializedName("messaging_product") val messagingProduct: String = "whatsapp",
    @SerializedName("to")                val to: String,
    @SerializedName("type")              val type: String = "audio",
    @SerializedName("audio")             val audio: WaMedia
)

data class WaMedia(
    @SerializedName("id")       val id: String? = null,    // uploaded media id
    @SerializedName("link")     val link: String? = null,  // public URL
    @SerializedName("caption")  val caption: String? = null
)

data class WaReadStatusRequest(
    @SerializedName("messaging_product") val messagingProduct: String = "whatsapp",
    @SerializedName("status")            val status: String = "read",
    @SerializedName("message_id")        val messageId: String
)

// ─── Responses ────────────────────────────────────────────────────────────────

data class WaMessageResponse(
    @SerializedName("messaging_product") val messagingProduct: String? = null,
    @SerializedName("contacts")          val contacts: List<WaContact>? = null,
    @SerializedName("messages")          val messages: List<WaMessageId>? = null,
    @SerializedName("error")             val error: WaError? = null
)

data class WaContact(
    @SerializedName("input") val input: String,
    @SerializedName("wa_id") val waId: String
)

data class WaMessageId(@SerializedName("id") val id: String)

data class WaReadStatusResponse(@SerializedName("success") val success: Boolean)

data class WaError(
    @SerializedName("message") val message: String,
    @SerializedName("code")    val code: Int
)

// ─── Incoming webhook payload ─────────────────────────────────────────────────

data class WaWebhookPayload(
    @SerializedName("object") val objectType: String,
    @SerializedName("entry")  val entry: List<WaEntry>
)

data class WaEntry(
    @SerializedName("id")      val id: String,
    @SerializedName("changes") val changes: List<WaChange>
)

data class WaChange(
    @SerializedName("value") val value: WaValue,
    @SerializedName("field") val field: String
)

data class WaValue(
    @SerializedName("messaging_product") val messagingProduct: String? = null,
    @SerializedName("messages")          val messages: List<WaInboundMessage>? = null,
    @SerializedName("contacts")          val contacts: List<WaInboundContact>? = null
)

data class WaInboundMessage(
    @SerializedName("id")        val id: String,
    @SerializedName("from")      val from: String,
    @SerializedName("type")      val type: String,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("text")      val text: WaInboundText? = null,
    @SerializedName("audio")     val audio: WaInboundAudio? = null
)

data class WaInboundText(@SerializedName("body") val body: String)
data class WaInboundAudio(@SerializedName("id") val id: String, @SerializedName("mime_type") val mimeType: String)
data class WaInboundContact(@SerializedName("profile") val profile: WaProfile, @SerializedName("wa_id") val waId: String)
data class WaProfile(@SerializedName("name") val name: String)
