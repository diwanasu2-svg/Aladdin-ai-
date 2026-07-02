package com.aladdin.app.llm

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ConversationContextWindow — Items 27, 28, 29: Token-aware conversation trimming.
 *
 * Manages the conversation history sent to the LLM so it never exceeds
 * the model's context window. Uses a sliding-window approach that keeps
 * the system prompt and most-recent messages while removing old turns.
 *
 * Fixes:
 *   - Item 27: Conversation history growing without bound → OOM / context overflow
 *   - Item 28: Context window not respected → LLM API errors
 *   - Item 29: System prompt lost when history is trimmed
 */
@Singleton
class ConversationContextWindow @Inject constructor() {
    companion object {
        private const val TAG = "ContextWindow"
        const val DEFAULT_MAX_TOKENS   = 4096
        const val DEFAULT_RESERVE      = 512    // reserve for the next LLM response
        private const val AVG_CHARS_PER_TOKEN = 4
    }

    data class Message(val role: String, val content: String) {
        val estimatedTokens: Int get() = (content.length / AVG_CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    private val _history = ArrayDeque<Message>()
    var systemPrompt: String = ""
        set(value) { field = value; Log.d(TAG, "System prompt set (${value.length} chars)") }

    var maxTokens: Int = DEFAULT_MAX_TOKENS
    var reservedTokens: Int = DEFAULT_RESERVE

    private val systemPromptTokens: Int
        get() = (systemPrompt.length / AVG_CHARS_PER_TOKEN).coerceAtLeast(0)

    private val availableTokens: Int
        get() = maxTokens - reservedTokens - systemPromptTokens

    // ── Public API ────────────────────────────────────────────────────────────

    fun addUserMessage(content: String) = addMessage("user", content)
    fun addAssistantMessage(content: String) = addMessage("assistant", content)

    fun addMessage(role: String, content: String) {
        _history.addLast(Message(role, content.trim()))
        trimToFit()
        Log.d(TAG, "Added [$role] (${_history.size} msgs, ~${usedTokens()} tokens)")
    }

    /**
     * Returns messages ready to send to the LLM — system prompt first,
     * then the trimmed history.
     */
    fun buildPromptMessages(): List<Message> {
        val out = mutableListOf<Message>()
        if (systemPrompt.isNotBlank()) out.add(Message("system", systemPrompt))
        out.addAll(_history)
        return out
    }

    /** Returns history as a single string suitable for simple LLM APIs. */
    fun buildPromptString(userInput: String): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) sb.appendLine("System: $systemPrompt\n")
        _history.forEach { msg -> sb.appendLine("${msg.role.replaceFirstChar { it.uppercase() }}: ${msg.content}") }
        sb.append("User: $userInput")
        return sb.toString()
    }

    fun clear() { _history.clear() }

    fun size(): Int = _history.size
    fun usedTokens(): Int = _history.sumOf { it.estimatedTokens } + systemPromptTokens
    fun getHistory(): List<Message> = _history.toList()

    // ── Trimming ──────────────────────────────────────────────────────────────

    private fun trimToFit() {
        val limit = availableTokens.coerceAtLeast(100)
        var total = _history.sumOf { it.estimatedTokens }
        var removed = 0
        // Remove from the front (oldest messages), always keep the last turn
        while (total > limit && _history.size > 2) {
            val evicted = _history.removeFirst()
            total -= evicted.estimatedTokens
            removed++
        }
        if (removed > 0) Log.d(TAG, "Trimmed $removed message(s) to fit context window ($total/$limit tokens)")
    }
}
