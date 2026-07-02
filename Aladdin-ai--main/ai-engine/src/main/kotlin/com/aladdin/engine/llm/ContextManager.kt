package com.aladdin.engine.llm

import android.util.Log
import com.aladdin.engine.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 — Context Manager
 *
 * Phase 4 upgrades:
 *   2. Conversation History to LLM — sends full relevant history; auto-summarises
 *      old turns with the LLM (not a placeholder) to stay within token budget.
 *   3. Better ContextManager — combines conversation, memory, user profile, and
 *      ongoing tasks; sends high-priority context first; deduplicates; auto-organises.
 *
 * Trimming strategy (Phase 4):
 *   1. Always keep the SYSTEM message
 *   2. Always keep the last MIN_KEEP_TURNS exchanges
 *   3. For removed messages: call LLM to generate a real summary
 *   4. Insert the real summary so no important context is lost
 *   5. De-duplicate repeated facts before sending to LLM
 */
@Singleton
class ContextManager @Inject constructor(
    private val llmClient: LLMClient
) {
    companion object {
        private const val TAG = "ContextManager"
        private const val MIN_KEEP_TURNS = 4
        private const val MAX_SUMMARY_SOURCE_CHARS = 2_000
    }

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages

    private var maxContextTokens: Int = 4096
    private var sessionId: String = UUID.randomUUID().toString()
    private var totalTokensEstimate: Int = 0

    // Enriched context segments — set externally from memory / profile / tasks
    private var memoryContextSegment: String = ""
    private var userProfileSegment: String = ""
    private var activeTasksSegment: String = ""

    // ─── Configuration ────────────────────────────────────────────────────────

    fun configure(maxTokens: Int) {
        maxContextTokens = maxTokens
    }

    fun startNewSession() {
        sessionId = UUID.randomUUID().toString()
        _messages.value = emptyList()
        totalTokensEstimate = 0
        memoryContextSegment = ""
        userProfileSegment = ""
        activeTasksSegment = ""
        Log.i(TAG, "New session: $sessionId")
    }

    // ─── Phase 4 #3 — Enriched Context Injection ──────────────────────────────

    /** Call this after retrieving memory context (from MemoryRouter / RAG). */
    fun setMemoryContext(memoryText: String) {
        memoryContextSegment = memoryText.take(800)
    }

    /** Call this with the user profile personalization string. */
    fun setUserProfileContext(profileText: String) {
        userProfileSegment = profileText.take(400)
    }

    /** Call this with a summary of the currently active tasks / goals. */
    fun setActiveTasksContext(tasksText: String) {
        activeTasksSegment = tasksText.take(400)
    }

    // ─── Message Management ───────────────────────────────────────────────────

    fun addSystemMessage(content: String) {
        val msg = ConversationMessage(
            role = MessageRole.SYSTEM,
            content = content,
            tokenCount = llmClient.estimateTokens(content)
        )
        updateMessages(_messages.value + msg)
    }

    fun addUserMessage(content: String, intentType: IntentType? = null): ConversationMessage {
        val msg = ConversationMessage(
            role = MessageRole.USER,
            content = content,
            intentType = intentType,
            tokenCount = llmClient.estimateTokens(content)
        )
        updateMessages(_messages.value + msg)
        return msg
    }

    fun addAssistantMessage(content: String, planId: String? = null): ConversationMessage {
        val msg = ConversationMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            planId = planId,
            tokenCount = llmClient.estimateTokens(content)
        )
        updateMessages(_messages.value + msg)
        return msg
    }

    fun addToolMessage(toolId: String, content: String): ConversationMessage {
        val msg = ConversationMessage(
            role = MessageRole.TOOL,
            content = content,
            toolId = toolId,
            tokenCount = llmClient.estimateTokens(content)
        )
        updateMessages(_messages.value + msg)
        return msg
    }

    // ─── Phase 4 #2 — Full Conversation History for LLM ──────────────────────

    /**
     * Returns the full relevant conversation history for LLM injection.
     * Includes all user and assistant turns (tool messages filtered out).
     * Deduplicates repeated content to avoid wasting tokens.
     */
    fun getMessagesForLLM(): List<ConversationMessage> {
        val filtered = _messages.value.filter { it.role != MessageRole.TOOL }
        return deduplicateMessages(filtered)
    }

    /**
     * Returns an enriched, priority-ordered context string combining:
     *   1. User profile (highest priority — personalises every reply)
     *   2. Active tasks/goals (high priority — current session intent)
     *   3. Memory context (medium priority — relevant facts)
     *   4. Recent conversation (always present)
     *
     * Deduplicates repeated facts across segments.
     */
    fun buildEnrichedContext(maxMessages: Int = 12): String {
        val sb = StringBuilder()

        if (userProfileSegment.isNotBlank()) {
            sb.appendLine("=== USER PROFILE ===")
            sb.appendLine(userProfileSegment)
            sb.appendLine()
        }

        if (activeTasksSegment.isNotBlank()) {
            sb.appendLine("=== ACTIVE GOALS & TASKS ===")
            sb.appendLine(activeTasksSegment)
            sb.appendLine()
        }

        if (memoryContextSegment.isNotBlank()) {
            sb.appendLine("=== RELEVANT MEMORY ===")
            sb.appendLine(deduplicateText(memoryContextSegment))
            sb.appendLine()
        }

        val recentTurns = _messages.value
            .filter { it.role in listOf(MessageRole.USER, MessageRole.ASSISTANT) }
            .takeLast(maxMessages)
        if (recentTurns.isNotEmpty()) {
            sb.appendLine("=== RECENT CONVERSATION ===")
            recentTurns.forEach { m ->
                val role = if (m.role == MessageRole.USER) "User" else "Aladdin"
                sb.appendLine("$role: ${m.content.take(200)}")
            }
        }

        return sb.toString().trim()
    }

    /** Legacy compat — used by ReasoningEngine and AIEngine. */
    fun buildRecentContext(maxMessages: Int = 10): String {
        return _messages.value
            .filter { it.role in listOf(MessageRole.USER, MessageRole.ASSISTANT) }
            .takeLast(maxMessages)
            .joinToString("\n") { "${it.role}: ${it.content.take(150)}" }
    }

    // ─── Phase 4 #2 — Real LLM Summarisation ──────────────────────────────────

    /**
     * Summarise old conversation turns using the LLM (Phase 4 upgrade).
     * Falls back to a simple extractive summary if the LLM call fails.
     */
    suspend fun summariseOldTurns(turns: List<ConversationMessage>): String {
        if (turns.isEmpty()) return ""
        val transcript = turns.joinToString("\n") { m ->
            val role = when (m.role) {
                MessageRole.USER      -> "User"
                MessageRole.ASSISTANT -> "Aladdin"
                else                  -> m.role.name
            }
            "$role: ${m.content.take(300)}"
        }.take(MAX_SUMMARY_SOURCE_CHARS)

        return try {
            val prompt = """
                You are summarising an earlier part of a conversation between a user and Aladdin (an AI assistant).
                Produce a concise, factual summary (3-5 sentences) that captures:
                - Key facts the user shared
                - Tasks or goals they mentioned
                - Important decisions or agreements made
                - Any preferences expressed
                
                Conversation to summarise:
                $transcript
                
                Summary:
            """.trimIndent()
            val summary = llmClient.complete(prompt, "You are a concise conversation summariser.")
            Log.d(TAG, "LLM summary generated: ${summary.take(60)}…")
            "[Earlier conversation summary: $summary]"
        } catch (e: Exception) {
            Log.w(TAG, "LLM summary failed, using extractive fallback: ${e.message}")
            extractiveSummary(turns)
        }
    }

    // ─── Other Accessors ──────────────────────────────────────────────────────

    fun getLastUserMessages(n: Int = 5): List<ConversationMessage> =
        _messages.value.filter { it.role == MessageRole.USER }.takeLast(n)

    fun getLastAssistantMessage(): ConversationMessage? =
        _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }

    val contextTokenCount: Int get() = totalTokensEstimate
    val messageCount: Int get() = _messages.value.size

    fun getSummaryText(): String {
        val turns = _messages.value
            .filter { it.role in listOf(MessageRole.USER, MessageRole.ASSISTANT) }
            .takeLast(20)
        return turns.joinToString("\n") { m ->
            val roleStr = if (m.role == MessageRole.USER) "User" else "Aladdin"
            "$roleStr: ${m.content.take(200)}"
        }
    }

    fun getSessionId(): String = sessionId

    fun flush() {
        Log.d(TAG, "flush() — ${messageCount} messages in context, " +
              "memory=${memoryContextSegment.length}chars, " +
              "profile=${userProfileSegment.length}chars")
    }

    // ─── Phase 4 — Context Trimming with Real LLM Summary ─────────────────────

    /**
     * Trim context to fit within [maxContextTokens].
     * Old turns are replaced by a real LLM-generated summary (async version
     * available via [trimAndSummarise]; this sync version uses extractive fallback).
     */
    private fun trimIfNeeded(messages: List<ConversationMessage>): List<ConversationMessage> {
        val total = messages.sumOf { it.tokenCount }
        if (total <= maxContextTokens) return messages

        Log.i(TAG, "Trimming context: $total tokens > $maxContextTokens limit")

        val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
        val nonSystem      = messages.filter { it.role != MessageRole.SYSTEM }
        val alwaysKeep     = nonSystem.takeLast(MIN_KEEP_TURNS * 2)
        val candidates     = nonSystem.dropLast(MIN_KEEP_TURNS * 2)

        if (candidates.isEmpty()) return messages

        var trimmed = candidates.toMutableList()
        var currentTotal = messages.sumOf { it.tokenCount }
        val removed = mutableListOf<ConversationMessage>()

        while (currentTotal > maxContextTokens && trimmed.isNotEmpty()) {
            val msg = trimmed.removeAt(0)
            removed.add(msg)
            currentTotal -= msg.tokenCount
        }

        val summaryText = extractiveSummary(removed)
        val summaryMsg = ConversationMessage(
            role = MessageRole.SYSTEM,
            content = summaryText,
            tokenCount = llmClient.estimateTokens(summaryText)
        )

        val result = systemMessages + listOf(summaryMsg) + trimmed + alwaysKeep
        Log.i(TAG, "Context trimmed: ${messages.size} → ${result.size} messages")
        return result
    }

    /**
     * Async version of trimming that calls the LLM for a real summary.
     * Call from a coroutine context when the full pipeline is available.
     */
    suspend fun trimAndSummarise() {
        val messages = _messages.value
        val total    = messages.sumOf { it.tokenCount }
        if (total <= maxContextTokens) return

        val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
        val nonSystem      = messages.filter { it.role != MessageRole.SYSTEM }
        val alwaysKeep     = nonSystem.takeLast(MIN_KEEP_TURNS * 2)
        val candidates     = nonSystem.dropLast(MIN_KEEP_TURNS * 2)

        if (candidates.isEmpty()) return

        var trimmed      = candidates.toMutableList()
        var currentTotal = total
        val removed      = mutableListOf<ConversationMessage>()

        while (currentTotal > maxContextTokens && trimmed.isNotEmpty()) {
            val msg = trimmed.removeAt(0)
            removed.add(msg)
            currentTotal -= msg.tokenCount
        }

        val summaryText = summariseOldTurns(removed)
        val summaryMsg  = ConversationMessage(
            role       = MessageRole.SYSTEM,
            content    = summaryText,
            tokenCount = llmClient.estimateTokens(summaryText)
        )

        val result = systemMessages + listOf(summaryMsg) + trimmed + alwaysKeep
        _messages.value       = result
        totalTokensEstimate   = result.sumOf { it.tokenCount }
        Log.i(TAG, "Async trim+summarise: ${messages.size} → ${result.size} messages")
    }

    // ─── Phase 4 #3 — Deduplication ───────────────────────────────────────────

    /**
     * Remove consecutive duplicate or near-duplicate messages.
     * Prevents the LLM from seeing the same fact repeated multiple times.
     */
    private fun deduplicateMessages(messages: List<ConversationMessage>): List<ConversationMessage> {
        val result  = mutableListOf<ConversationMessage>()
        val seen    = mutableSetOf<String>()
        for (msg in messages) {
            val key = msg.content.take(60).lowercase().trim()
            if (key !in seen || msg.role == MessageRole.SYSTEM) {
                result.add(msg)
                seen.add(key)
            }
        }
        return result
    }

    /**
     * Remove duplicate sentences from a text block.
     */
    private fun deduplicateText(text: String): String {
        val lines = text.split(Regex("\n+"))
        val seen  = mutableSetOf<String>()
        return lines.filter { line ->
            val key = line.trim().lowercase()
            key.isBlank() || seen.add(key)
        }.joinToString("\n")
    }

    private fun updateMessages(newList: List<ConversationMessage>) {
        val trimmed = trimIfNeeded(newList)
        _messages.value     = trimmed
        totalTokensEstimate = trimmed.sumOf { it.tokenCount }
    }

    // ─── Extractive Summary Fallback ──────────────────────────────────────────

    private fun extractiveSummary(turns: List<ConversationMessage>): String {
        if (turns.isEmpty()) return "[Earlier conversation: (empty)]"
        val userFacts = turns
            .filter { it.role == MessageRole.USER }
            .map { it.content.take(120) }
            .distinct()
            .take(5)
        val summary = userFacts.joinToString("; ")
        return "[Earlier conversation summary: User discussed — $summary]"
    }
}
