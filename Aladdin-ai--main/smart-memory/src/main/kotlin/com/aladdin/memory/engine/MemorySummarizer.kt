package com.aladdin.memory.engine

import android.util.Log
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM-based conversation summarizer with context window compression.
 *
 * Uses extractive summarization (no external LLM call required).
 * All Ollama HTTP bridge code has been removed — the app uses Gemini
 * exclusively, which is only available from the main app module.
 */
@Singleton
class MemorySummarizer @Inject constructor() {

    companion object {
        private const val TAG = "MemorySummarizer"
        private const val MAX_SUMMARY_WORDS = 150
        private const val MIN_MEMORIES_TO_SUMMARIZE = 5
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Summarize a batch of conversation memories into a single summary string.
     * Uses extractive summarization.
     */
    suspend fun summarize(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""
        return extractiveSummary(memories)
    }

    /**
     * Compress a list of memories to fit a context window of [maxTokens].
     * Returns a list that fits within the budget, prioritized by importance.
     */
    fun compressForContext(
        memories: List<MemoryEntity>,
        maxTokens: Int = 2048
    ): List<MemoryEntity> {
        var tokenBudget = maxTokens
        val result = mutableListOf<MemoryEntity>()

        val sorted = memories.sortedWith(
            compareByDescending<MemoryEntity> { it.importanceScore }
                .thenByDescending { it.memoryType == MemoryType.FACT }
                .thenByDescending { it.lastAccessedAt }
        )

        for (memory in sorted) {
            val text = memory.summary ?: memory.content
            val tokens = (text.split("\\s+".toRegex()).size * 1.3).toInt()
            if (tokens <= tokenBudget) {
                result.add(memory)
                tokenBudget -= tokens
            }
            if (tokenBudget <= 0) break
        }

        return result
    }

    /**
     * Returns a condensed context string from [memories] for LLM injection.
     */
    fun buildContextString(memories: List<MemoryEntity>): String {
        return memories.joinToString("\n") { m ->
            val text = m.summary?.take(200) ?: m.content.take(200)
            "[${m.memoryType}] $text"
        }
    }

    // ─── Extractive Summarization ─────────────────────────────────────────────

    private fun extractiveSummary(memories: List<MemoryEntity>): String {
        val allText = memories.joinToString(" ") { m ->
            m.summary ?: m.content
        }

        val sentences = allText
            .split(Regex("[.!?]+"))
            .map { it.trim() }
            .filter { it.split(" ").size >= 4 }
            .take(30)

        if (sentences.isEmpty()) return allText.take(300)

        val wordFreq = HashMap<String, Int>()
        for (sent in sentences) {
            for (word in sent.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }) {
                wordFreq[word] = (wordFreq[word] ?: 0) + 1
            }
        }

        val scored = sentences.map { sent ->
            val score = sent.lowercase()
                .split("\\s+".toRegex())
                .filter { it.length > 3 }
                .sumOf { wordFreq[it]?.toDouble() ?: 0.0 }
            Pair(sent, score)
        }.sortedByDescending { it.second }

        var wordCount = 0
        val selected = mutableListOf<String>()
        for ((sent, _) in scored) {
            val words = sent.split("\\s+".toRegex()).size
            if (wordCount + words <= MAX_SUMMARY_WORDS) {
                selected.add(sent)
                wordCount += words
            }
            if (wordCount >= MAX_SUMMARY_WORDS) break
        }

        return selected.joinToString(". ").trimEnd('.') + "."
    }
}
