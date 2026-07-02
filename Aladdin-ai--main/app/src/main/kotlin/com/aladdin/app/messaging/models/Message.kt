package com.aladdin.app.messaging.models

/**
 * Unified message model shared across all platforms.
 * Each platform adapter maps its native response to this format.
 */
data class Message(
    val id: String,
    val platform: Platform,
    val chatId: String,                  // channel/chat/email thread id
    val senderId: String,
    val senderName: String,
    val text: String,
    val mediaUrl: String? = null,        // photo, audio, video, file
    val mediaType: MediaType? = null,
    val replyToId: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val isFromBot: Boolean = false
)

enum class Platform { TELEGRAM, WHATSAPP, DISCORD, EMAIL, FCM }

enum class MediaType { IMAGE, AUDIO, VIDEO, DOCUMENT, VOICE }

/** Outgoing message payload — common across platforms */
data class OutgoingMessage(
    val platform: Platform,
    val chatId: String,
    val text: String? = null,
    val voiceFilePath: String? = null,   // local OGG/MP3 path for voice replies
    val imageFilePath: String? = null,
    val replyToId: String? = null
)

/** Result of a send operation */
sealed class SendResult {
    data class Success(val messageId: String) : SendResult()
    data class Failure(val error: String, val cause: Throwable? = null) : SendResult()
}
