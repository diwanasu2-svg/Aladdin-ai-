package com.aladdin.internet.summarizer

import com.aladdin.internet.model.RankedResult
import com.aladdin.internet.model.SearchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM-based summarizer that condenses ranked search results into a concise answer.
 *
 * Pluggable backend — defaults to extractive summarization with a scoring heuristic.
 * For a real LLM, set [llmBackend] to your on-device or remote model adapter.
 */
class LLMSummarizer(private val llmBackend: LLMBackend? = null) {

    suspend fun summarize(
        query: String,
        results: List<RankedResult>,
        type: SearchType,
        maxSentences: Int = 4
    ): String = withContext(Dispatchers.Default) {
        if (results.isEmpty()) return@withContext "No results found for: \"$query\""

        llmBackend?.summarize(query, results, type)
            ?: extractiveSummarize(query, results, type, maxSentences)
    }

    // ── Extractive fallback ────────────────────────────────────────────────

    private fun extractiveSummarize(
        query: String,
        results: List<RankedResult>,
        type: SearchType,
        maxSentences: Int
    ): String {
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()

        // Collect sentences from top results
        val allSentences = results.take(5).flatMap { ranked ->
            val text = ranked.result.snippet.ifBlank { ranked.result.title }
            splitSentences(text).map { sentence -> sentence to ranked.result.finalScore }
        }

        if (allSentences.isEmpty()) {
            return results.first().result.let { "${it.title}: ${it.snippet}" }
        }

        // Score sentences by query term coverage + position weight
        val scoredSentences = allSentences.mapIndexed { idx, (sentence, resultScore) ->
            val terms       = sentence.lowercase().split(Regex("\\s+"))
            val coverage    = terms.count { it in queryTerms }.toFloat() / queryTerms.size.coerceAtLeast(1)
            val posWeight   = 1f / (idx + 1).toFloat()
            val finalScore  = (coverage * 0.6f + posWeight * 0.2f + resultScore * 0.2f)
            sentence to finalScore
        }

        val topSentences = scoredSentences
            .sortedByDescending { it.second }
            .take(maxSentences)
            .map { it.first }
            .distinct()

        val typePrefix = when (type) {
            SearchType.NEWS       -> "Latest news: "
            SearchType.WIKIPEDIA  -> "According to Wikipedia: "
            SearchType.DEFINITION -> "Definition: "
            SearchType.WEATHER    -> "Weather: "
            SearchType.IMAGES     -> "Images found: "
            else                  -> ""
        }

        return typePrefix + topSentences.joinToString(" ").trim()
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 20 }
}

/**
 * Interface for plugging in a real LLM backend (on-device or remote).
 * Implement this to use Gemini Nano, Llama, OpenAI, etc.
 */
interface LLMBackend {
    suspend fun summarize(
        query: String,
        results: List<RankedResult>,
        type: SearchType
    ): String
}
