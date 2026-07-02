package com.aladdin.app.messaging.email

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
//  Gmail REST API v1
//  Base URL: https://gmail.googleapis.com/gmail/v1/
//  Auth: Authorization: Bearer {OAUTH2_ACCESS_TOKEN}
// ─────────────────────────────────────────────────────────────────────────────

interface GmailApi {

    /** List messages in the inbox (or matching a query). */
    @GET("users/{userId}/messages")
    suspend fun listMessages(
        @Path("userId")          userId: String = "me",
        @Query("q")              query: String = "in:inbox is:unread",
        @Query("maxResults")     maxResults: Int = 20,
        @Header("Authorization") auth: String
    ): Response<GmailListResponse>

    /** Get a single message (full payload). */
    @GET("users/{userId}/messages/{messageId}")
    suspend fun getMessage(
        @Path("userId")          userId: String = "me",
        @Path("messageId")       messageId: String,
        @Query("format")         format: String = "full",
        @Header("Authorization") auth: String
    ): Response<GmailMessage>

    /** Send an email (base64url-encoded RFC 2822 message). */
    @POST("users/{userId}/messages/send")
    suspend fun sendMessage(
        @Path("userId")          userId: String = "me",
        @Body                    body: GmailSendRequest,
        @Header("Authorization") auth: String
    ): Response<GmailSendResponse>

    /** Mark message as read. */
    @POST("users/{userId}/messages/{messageId}/modify")
    suspend fun modifyMessage(
        @Path("userId")          userId: String = "me",
        @Path("messageId")       messageId: String,
        @Body                    body: GmailModifyRequest,
        @Header("Authorization") auth: String
    ): Response<GmailMessage>

    /** Get authenticated user profile. */
    @GET("users/{userId}/profile")
    suspend fun getProfile(
        @Path("userId")          userId: String = "me",
        @Header("Authorization") auth: String
    ): Response<GmailProfile>
}

// ─── Response models ──────────────────────────────────────────────────────────

data class GmailListResponse(
    @SerializedName("messages")           val messages: List<GmailMessageRef>? = null,
    @SerializedName("nextPageToken")      val nextPageToken: String? = null,
    @SerializedName("resultSizeEstimate") val resultSizeEstimate: Int = 0
)

data class GmailMessageRef(
    @SerializedName("id")       val id: String,
    @SerializedName("threadId") val threadId: String
)

data class GmailMessage(
    @SerializedName("id")          val id: String,
    @SerializedName("threadId")    val threadId: String,
    @SerializedName("labelIds")    val labelIds: List<String> = emptyList(),
    @SerializedName("snippet")     val snippet: String = "",
    @SerializedName("payload")     val payload: GmailPayload? = null,
    @SerializedName("internalDate") val internalDate: String = "0"
)

data class GmailPayload(
    @SerializedName("partId")   val partId: String = "",
    @SerializedName("mimeType") val mimeType: String = "",
    @SerializedName("headers")  val headers: List<GmailHeader> = emptyList(),
    @SerializedName("body")     val body: GmailBody? = null,
    @SerializedName("parts")    val parts: List<GmailPayload>? = null
)

data class GmailHeader(
    @SerializedName("name")  val name: String,
    @SerializedName("value") val value: String
)

data class GmailBody(
    @SerializedName("size") val size: Int = 0,
    @SerializedName("data") val data: String? = null   // base64url encoded
)

data class GmailProfile(
    @SerializedName("emailAddress")  val emailAddress: String,
    @SerializedName("messagesTotal") val messagesTotal: Int = 0
)

// ─── Request bodies ───────────────────────────────────────────────────────────

data class GmailSendRequest(
    @SerializedName("raw") val raw: String   // base64url RFC 2822
)

data class GmailSendResponse(
    @SerializedName("id")       val id: String,
    @SerializedName("threadId") val threadId: String,
    @SerializedName("labelIds") val labelIds: List<String>
)

data class GmailModifyRequest(
    @SerializedName("removeLabelIds") val removeLabelIds: List<String> = listOf("UNREAD"),
    @SerializedName("addLabelIds")    val addLabelIds: List<String> = emptyList()
)
