package com.aladdin.memory.engine

import android.util.Log
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM-based conversation summarizer with context window compression.
 *
 * Two modes:
 *   1. Local rule-based compression (always available, no model required)
 *   2. LLM bridge: sends to a local LLM (e.g., Aladdin's main LLM service)
 *      via HTTP POST to http://127.0.0.1:PORT/summarize
 *
 * The summarizer is called by [MemorySummaryWorker] nightly.
 *
 * Context window compression:
 *   - Merge N conversation turns into a single SUMMARY memory.
 *   - Preserve FACT, PREFERENCE, and EVENT memories as-is.
 *   - Discard low-importance CONVERSATION memories post-summarization.
 */
@Singleton
class MemorySummarizer @Inject constructor() {

    companion object {
        private const val TAG = "MemorySummarizer"
        private const val LLM_PORT = 11434          // Ollama default port
        private const val MAX_SUMMARY_WORDS = 150
        private const val MIN_MEMORIES_TO_SUMMARIZE = 5
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Summarize a batch of conversation memories into a single summary string.
     * Tries the LLM bridge first; falls back to extractive summarization.
     */
    suspend fun summarize(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""
        if (memories.size < MIN_MEMORIES_TO_SUMMARIZE) {
            return extractiveSummary(memories)
        }

        return try {
            llmSummarize(memories) ?: extractiveSummary(memories)
        } catch (e: Exception) {
            Log.w(TAG, "LLM summarize failed: ${e.message} – using extractive fallback")
            extractiveSummary(memories)
        }
    }

    /**
     * Compress a list of memories to fit a context window of [maxTokens].
     * Returns a list that fits within the budget, prioritized by importance.
     */
    fun compressForContext(
        memories: List<MemoryEntity>,
        maxTokens: Int = 2048
    ): List<MemoryEntity> {
        // Rough token estimate: 1 word ≈ 1.3 tokens
        var tokenBudget = maxTokens
        val result = mutableListOf<MemoryEntity>()

        // Priority: FACT > PREFERENCE > PROJECT > SUMMARY > EVENT > CONVERSATION
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
     * Format: "<type>: <text>" per line.
     */
    fun buildContextString(memories: List<MemoryEntity>): String {
        return memories.joinToString("\n") { m ->
            val text = m.summary?.take(200) ?: m.content.take(200)
            "[${m.memoryType}] $text"
        }
    }

    // ─── LLM Bridge ───────────────────────────────────────────────────────────

    private suspend fun llmSummarize(memories: List<MemoryEntity>): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conversation = memories.joinToString("\n") { m ->
                    "- ${m.content.take(300)}"
                }
                val prompt = buildSummarizationPrompt(conversation)

                val url = java.net.URL("http://127.0.0.1:$LLM_PORT/api/generate")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 30_000

                val body = """{"model":"mistral","prompt":${escapeJson(prompt)},"stream":false}"""
                conn.outputStream.bufferedWriter().use { it.write(body) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    extractOllamaResponse(response)
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    // ─── Extractive Summarization (Fallback) ──────────────────────────────────

    /**
     * Simple extractive summarization:
     *   1. Score each sentence by keyword overlap with other sentences (TF-IDF approximation).
     *   2. Return top sentences up to [MAX_SUMMARY_WORDS].
     */
    private fun extractiveSummary(memories: List<MemoryEntity>): String {
        // Concatenate all content
        val allText = memories.joinToString(" ") { m ->
            m.summary ?: m.content
        }

        // Split into sentences
        val sentences = allText
            .split(Regex("[.!?]+"))
            .map { it.trim() }
            .filter { it.split(" ").size >= 4 }
            .take(30)

        if (sentences.isEmpty()) return allText.take(300)

        // Score sentences by word frequency
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildSummarizationPrompt(conversation: String): String = """
        You are a concise memory assistant. Summarize the following conversation history 
        into a compact, factual summary of at most $MAX_SUMMARY_WORDS words.
        Preserve names, dates, preferences, and important facts. 
        Use third-person perspective (e.g., "The user...").
        
        Conversation:
        $conversation
        
        Summary:
    """.trimIndent()

    private fun extractOllamaResponse(json: String): String? {
        val key = "\"response\":"
        val start = json.indexOf(key)
        if (start < 0) return null
        val valStart = json.indexOf('"', start + key.length) + 1
        val valEnd = json.indexOf('"', valStart)
        return if (valStart > 0 && valEnd > valStart) {
            json.substring(valStart, valEnd)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .trim()
        } else null
    }

    private fun escapeJson(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
