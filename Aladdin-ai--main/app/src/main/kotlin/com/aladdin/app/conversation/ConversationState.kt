package com.aladdin.app.conversation

import java.time.Instant

// ─── Conversation States ──────────────────────────────────────────────────────

sealed class ConversationState {
    object Idle       : ConversationState()
    object Listening  : ConversationState()
    object Thinking   : ConversationState()
    data class Speaking(val text: String) : ConversationState()
    data class Error(val message: String) : ConversationState()
}

// ─── Turn model ───────────────────────────────────────────────────────────────

data class ConversationTurn(
    val role: Role,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class Role(val label: String) {
    USER("user"),
    ASSISTANT("model"),   // Gemini uses "model" for assistant turns
    SYSTEM("system")
}
