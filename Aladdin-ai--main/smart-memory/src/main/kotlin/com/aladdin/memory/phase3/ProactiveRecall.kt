package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.ContactDao
import com.aladdin.memory.db.dao.ReminderDao
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.model.Memory
import com.aladdin.memory.model.MemorySearchResult
import com.aladdin.memory.model.SearchFilter
import com.aladdin.memory.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive Memory Recall
 *
 * Automatically injects relevant memories into conversation context WITHOUT
 * the user explicitly asking. Mimics human-like "oh by the way..." recall.
 *
 * Strategy:
 *  1. Semantic similarity to current conversation
 *  2. Contact mentions → inject their memory context
 *  3. Upcoming reminders → surface proactively
 *  4. Calendar/time-based recall (morning briefing, evening review)
 *  5. Preference injection for personalization
 *  6. Previous chat context continuity
 */
@Singleton
class ProactiveRecall @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val contactDao: ContactDao,
    private val reminderDao: ReminderDao,
    private val userProfileEvolution: UserProfileEvolution
) {
    companion object {
        private const val TAG = "ProactiveRecall"
        private const val SEMANTIC_THRESHOLD = 0.55f
        private const val MAX_INJECTED = 8
        private const val REMINDER_WINDOW_MS = 3_600_000L   // 1 hour ahead
    }

    // ─── Main Entry Point ─────────────────────────────────────────────────────

    /**
     * Given the current conversation [userMessage] and recent [conversationHistory],
     * returns a [ProactiveContext] with memories the AI should be aware of.
     *
     * Call this BEFORE generating each AI response.
     */
    suspend fun recall(
        userMessage: String,
        conversationHistory: List<String> = emptyList(),
        sessionId: String? = null
    ): ProactiveContext = withContext(Dispatchers.IO) {

        val contextQuery = buildContextQuery(userMessage, conversationHistory)

        // Run all recall strategies in parallel
        val semanticAsync = async { recallSemantic(contextQuery) }
        val contactAsync = async { recallContactMentions(userMessage) }
        val reminderAsync = async { recallUpcomingReminders() }
        val preferenceAsync = async { recallUserPreferences(contextQuery) }
        val sessionAsync = async { recallSessionContinuity(sessionId) }
        val habitsAsync = async { recallRelevantHabits(contextQuery) }

        val semantic = semanticAsync.await()
        val contacts = contactAsync.await()
        val reminders = reminderAsync.await()
        val preferences = preferenceAsync.await()
        val sessionContinuity = sessionAsync.await()
        val habits = habitsAsync.await()

        // Merge and de-duplicate
        val allMemories = (semantic + preferences + sessionContinuity)
            .distinctBy { it.memory.id }
            .sortedByDescending { it.score }
            .take(MAX_INJECTED)

        val ctx = ProactiveContext(
            memories = allMemories,
            contactContext = contacts,
            upcomingReminders = reminders,
            relevantHabits = habits,
            contextQuery = contextQuery
        )

        Log.d(TAG, "Proactive recall: ${ctx.memories.size} memories, ${ctx.contactContext.size} contacts, ${ctx.upcomingReminders.size} reminders")
        ctx
    }

    // ─── Semantic Recall ──────────────────────────────────────────────────────

    private suspend fun recallSemantic(query: String): List<MemorySearchResult> {
        if (query.isBlank()) return emptyList()
        return try {
            memoryRepository.semanticSearch(query, k = 10)
                .filter { it.score >= SEMANTIC_THRESHOLD }
        } catch (e: Exception) {
            Log.w(TAG, "Semantic recall failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Contact Mention Recall ───────────────────────────────────────────────

    private suspend fun recallContactMentions(message: String): List<ContactMemoryContext> {
        val mentionedNames = extractMentionedNames(message)
        if (mentionedNames.isEmpty()) return emptyList()

        return mentionedNames.flatMap { name ->
            val contacts = contactDao.search(name, 3)
            contacts.map { contact ->
                val contactMemories = memoryRepository.search(
                    contact.name,
                    k = 5,
                    filter = SearchFilter(contactId = contact.id)
                )
                ContactMemoryContext(contact = contact.name, memories = contactMemories)
            }
        }
    }

    // ─── Upcoming Reminder Recall ─────────────────────────────────────────────

    private suspend fun recallUpcomingReminders(): List<String> {
        val now = System.currentTimeMillis()
        val soon = now + REMINDER_WINDOW_MS
        return try {
            reminderDao.getDue(now, soon).map { reminder ->
                "UPCOMING: ${reminder.title} — ${reminder.body}"
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Preference Recall ────────────────────────────────────────────────────

    private suspend fun recallUserPreferences(query: String): List<MemorySearchResult> {
        return try {
            memoryRepository.search(
                query, k = 5,
                filter = SearchFilter(memoryTypes = setOf(MemoryType.PREFERENCE))
            ).filter { it.score >= 0.45f }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Session Continuity ───────────────────────────────────────────────────

    private suspend fun recallSessionContinuity(sessionId: String?): List<MemorySearchResult> {
        if (sessionId == null) return emptyList()
        return try {
            val sessionMemories = memoryRepository.getBySession(sessionId)
            sessionMemories.sortedByDescending { it.createdAt }
                .take(5)
                .map { MemorySearchResult(it, score = 0.7f) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Habit Recall ─────────────────────────────────────────────────────────

    private suspend fun recallRelevantHabits(query: String): List<String> {
        return try {
            memoryRepository.search(
                query, k = 3,
                filter = SearchFilter(memoryTypes = setOf(MemoryType.FACT))
            ).filter { it.score >= 0.6f }.map { it.memory.content.take(150) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Context String ────────────────────────────────────────────────────────

    /**
     * Builds a context string from [ProactiveContext] ready for LLM injection.
     * Formatted as a system prompt supplement.
     */
    fun buildInjectionPrompt(ctx: ProactiveContext): String {
        if (ctx.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[PROACTIVE MEMORY CONTEXT — Use naturally, don't cite verbatim]")

        if (ctx.memories.isNotEmpty()) {
            sb.appendLine("\nRELEVANT MEMORIES:")
            ctx.memories.take(6).forEach { r ->
                sb.appendLine("• ${r.memory.content.take(200)}")
            }
        }

        if (ctx.contactContext.isNotEmpty()) {
            sb.appendLine("\nCONTACT CONTEXT:")
            ctx.contactContext.forEach { cc ->
                sb.appendLine("• ${cc.contact}:")
                cc.memories.take(3).forEach { m ->
                    sb.appendLine("  - ${m.memory.content.take(120)}")
                }
            }
        }

        if (ctx.upcomingReminders.isNotEmpty()) {
            sb.appendLine("\nUPCOMING EVENTS:")
            ctx.upcomingReminders.forEach { sb.appendLine("• $it") }
        }

        if (ctx.relevantHabits.isNotEmpty()) {
            sb.appendLine("\nUSER PATTERNS:")
            ctx.relevantHabits.forEach { sb.appendLine("• $it") }
        }

        return sb.toString().trim()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildContextQuery(message: String, history: List<String>): String {
        val recent = history.takeLast(4)
        return (recent + message).joinToString(" ").take(512)
    }

    private fun extractMentionedNames(text: String): List<String> {
        // Simple heuristic: capitalized words not at start of sentence
        val words = text.split("\\s+".toRegex())
        val names = mutableListOf<String>()
        for (i in 1 until words.size) {
            val word = words[i].replace(Regex("[^a-zA-Z]"), "")
            if (word.isNotBlank() && word[0].isUpperCase() && word.length > 2) {
                names.add(word)
            }
        }
        return names.distinct()
    }
}

data class ProactiveContext(
    val memories: List<MemorySearchResult> = emptyList(),
    val contactContext: List<ContactMemoryContext> = emptyList(),
    val upcomingReminders: List<String> = emptyList(),
    val relevantHabits: List<String> = emptyList(),
    val contextQuery: String = ""
) {
    fun isEmpty() = memories.isEmpty() && contactContext.isEmpty() &&
        upcomingReminders.isEmpty() && relevantHabits.isEmpty()
}

data class ContactMemoryContext(
    val contact: String,
    val memories: List<MemorySearchResult> = emptyList()
)
