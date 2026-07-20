package com.aladdin.assistant.data.model

import androidx.room.*
import kotlinx.serialization.Serializable
import java.util.UUID

// ─── Message Role ─────────────────────────────────────────────────────────────
enum class MessageRole { USER, ASSISTANT, SYSTEM }

// ─── Chat Message Entity ──────────────────────────────────────────────────────
@Entity(tableName = "messages", foreignKeys = [
    ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )
], indices = [Index("conversationId")])
@Serializable
data class ChatMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

// ─── Conversation Entity ──────────────────────────────────────────────────────
@Entity(tableName = "conversations")
@Serializable
data class Conversation(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "New Conversation",
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val messageCount: Int = 0
)

// ─── Conversation with last message ───────────────────────────────────────────
data class ConversationWithLastMessage(
    val conversation: Conversation,
    val lastMessage: ChatMessage?
)

// ─── App Settings ─────────────────────────────────────────────────────────────
@Serializable
data class AppSettings(
    val aiProvider: String = "gemini",
    val aiModel: String = "gemini-1.5-flash",
    val apiKey: String = "",
    val voiceEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val wakeWord: String = "Hey Aladdin",
    val language: String = "en-US",
    val speechRate: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val appTheme: String = "SYSTEM",
    val dynamicColor: Boolean = true,
    val accentColor: String = "Purple",
    val fontSize: Float = 1.0f,
    val notificationsEnabled: Boolean = true,
    val backgroundAssistant: Boolean = false,
    val privacyMode: Boolean = false,
    val autoDeleteHistory: Int = 0,
    val hapticFeedback: Boolean = true,
    val soundEffects: Boolean = true
)

// ─── Voice State ──────────────────────────────────────────────────────────────
enum class VoiceState {
    IDLE, LISTENING, PROCESSING, SPEAKING, WAKE_WORD_DETECTED, ERROR
}

// ─── AI Provider ──────────────────────────────────────────────────────────────
data class AIProvider(
    val id: String,
    val name: String,
    val models: List<String>,
    val requiresKey: Boolean = true
)

val SUPPORTED_PROVIDERS = listOf(
    AIProvider("gemini",    "Google Gemini",      listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")),
    AIProvider("openai",    "OpenAI",             listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")),
    AIProvider("anthropic", "Anthropic Claude",   listOf("claude-3-5-sonnet-20241022", "claude-3-haiku-20240307")),
    AIProvider("groq",      "Groq",               listOf("llama-3.1-70b-versatile", "mixtral-8x7b-32768"))
)
