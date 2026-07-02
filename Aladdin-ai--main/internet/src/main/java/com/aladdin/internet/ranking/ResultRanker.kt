package com.aladdin.internet.ranking

import com.aladdin.internet.model.RankedResult
import com.aladdin.internet.model.SearchResult
import com.aladdin.internet.model.SearchSource
import com.aladdin.internet.model.SearchType

/**
 * Ranks search results using a composite score of Relevance + Authority.
 *
 * Relevance Score (0–1):  query term overlap, snippet length, title match
 * Authority Score  (0–1): source tier, freshness, content richness
 * Final Score      (0–1): weighted combination (60% relevance, 40% authority)
 */
class ResultRanker {

    // Source authority weights (domain expertise per search type)
    private val sourceAuthorityWeights = mapOf(
        SearchSource.WIKIPEDIA   to 0.95f,
        SearchSource.BRAVE_SEARCH to 0.85f,
        SearchSource.SERPER      to 0.85f,
        SearchSource.NEWS_API    to 0.80f,
        SearchSource.DUCKDUCKGO  to 0.75f,
        SearchSource.CACHE       to 0.70f
    )

    fun rank(results: List<SearchResult>, query: String, type: SearchType): List<RankedResult> {
        if (results.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)

        val scored = results.map { result ->
            val relevance  = computeRelevance(result, queryTerms)
            val authority  = computeAuthority(result, type)
            val finalScore = 0.60f * relevance + 0.40f * authority

            result.copy(
                relevanceScore = relevance,
                authorityScore = authority,
                finalScore     = finalScore
            )
        }

        return scored
            .sortedByDescending { it.finalScore }
            .mapIndexed { idx, result ->
                RankedResult(
                    result      = result,
                    rank        = idx + 1,
                    explanation = buildExplanation(result, idx + 1)
                )
            }
    }

    // ── Relevance ──────────────────────────────────────────────────────────

    private fun computeRelevance(result: SearchResult, queryTerms: List<String>): Float {
        if (queryTerms.isEmpty()) return 0.5f

        val titleTerms   = tokenize(result.title)
        val snippetTerms = tokenize(result.snippet)

        val titleMatch   = termOverlap(queryTerms, titleTerms)
        val snippetMatch = termOverlap(queryTerms, snippetTerms)

        // Exact phrase bonus in title
        val exactTitleBonus = if (result.title.contains(queryTerms.joinToString(" "), ignoreCase = true)) 0.2f else 0f

        // Snippet richness bonus
        val snippetLenScore = minOf(result.snippet.length / 200f, 1f) * 0.1f

        val raw = (titleMatch * 0.5f) + (snippetMatch * 0.3f) + exactTitleBonus + snippetLenScore
        return raw.coerceIn(0f, 1f)
    }

    private fun termOverlap(queryTerms: List<String>, docTerms: List<String>): Float {
        if (queryTerms.isEmpty() || docTerms.isEmpty()) return 0f
        val docSet = docTerms.map { it.lowercase() }.toHashSet()
        val matches = queryTerms.count { it.lowercase() in docSet }
        return matches.toFloat() / queryTerms.size
    }

    // ── Authority ──────────────────────────────────────────────────────────

    private fun computeAuthority(result: SearchResult, type: SearchType): Float {
        val sourceScore = sourceAuthorityWeights[result.source] ?: 0.70f

        // Boost Wikipedia for definitions/factual queries
        val typeSourceBonus = when {
            type == SearchType.WIKIPEDIA  && result.source == SearchSource.WIKIPEDIA    -> 0.15f
            type == SearchType.NEWS       && result.source == SearchSource.NEWS_API      -> 0.10f
            type == SearchType.IMAGES     && result.source == SearchSource.BRAVE_SEARCH  -> 0.10f
            else -> 0f
        }

        // Freshness bonus for news (articles with publishedAt set)
        val freshnessScore = if (!result.publishedAt.isNullOrBlank()) 0.10f else 0f

        // Image thumbnail bonus
        val imageBonus = if (!result.imageUrl.isNullOrBlank()) 0.05f else 0f

        return (sourceScore + typeSourceBonus + freshnessScore + imageBonus).coerceIn(0f, 1f)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }   // drop stop-words shorter than 3 chars

    private fun buildExplanation(result: SearchResult, rank: Int): String =
        "Rank #$rank | Score: %.2f (Relevance: %.2f, Authority: %.2f) | Source: ${result.source}"
            .format(result.finalScore, result.relevanceScore, result.authorityScore)
}
