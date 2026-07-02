import dagger.hilt.android.qualifiers.ApplicationContext

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phase 5 – Research Agent (Medium Priority)
 *
 * Responsibilities:
 *  - Search the internet for information
 *  - Compare and aggregate multiple sources
 *  - Filter fake or duplicate results
 *  - Create summaries
 *  - Rank sources by credibility
 *  - Retrieve the latest information
 */
@Singleton
class ResearchAgent @Inject constructor(
    private val safetyAgent: SafetyAgent,
    private val memoryAgent: MemoryAgent
) {
    companion object {
        private const val TAG = "ResearchAgent"

        // Credibility score by domain (0–1)
        private val DOMAIN_CREDIBILITY = mapOf(
            "wikipedia.org"  to 0.85f,
            "arxiv.org"      to 0.95f,
            "nature.com"     to 0.95f,
            "gov"            to 0.90f,
            "edu"            to 0.88f,
            "bbc.com"        to 0.80f,
            "reuters.com"    to 0.82f,
            "apnews.com"     to 0.83f,
            "medium.com"     to 0.50f,
            "reddit.com"     to 0.35f
        )
    }

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String,
        val credibilityScore: Float,
        val publishedAt: String = "",
        val isVerified: Boolean = false
    )

    data class ResearchReport(
        val query: String,
        val summary: String,
        val sources: List<SearchResult>,
        val keyFacts: List<String>,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val searchCache = mutableMapOf<String, ResearchReport>()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.RESEARCH ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Research Agent started")
    }

    // ── Core research pipeline ──────────────────────────────────────────────

    suspend fun research(query: String, maxSources: Int = 5): ResearchReport {
        // Safety check
        val safetyReport = safetyAgent.validate(query)
        if (!safetyReport.isSafe) {
            return ResearchReport(
                query = query,
                summary = "Query blocked by Safety Agent: ${safetyReport.reason}",
                sources = emptyList(),
                keyFacts = emptyList(),
                confidence = 0f
            )
        }

        // Cache check
        searchCache[query.lowercase()]?.let {
            if (System.currentTimeMillis() - it.timestamp < 5 * 60 * 1000) {
                Log.d(TAG, "Cache hit for query: $query")
                return it
            }
        }

        Log.d(TAG, "Researching: $query")
        val results = searchInternet(query, maxSources)
        val filtered = filterAndDeduplicate(results)
        val ranked = rankByCredibility(filtered)
        val summary = summarise(query, ranked)
        val facts = extractKeyFacts(ranked)

        val confidence = ranked.map { it.credibilityScore }.average().toFloat()

        val report = ResearchReport(
            query = query,
            summary = summary,
            sources = ranked,
            keyFacts = facts,
            confidence = confidence
        )

        // Cache and memorise
        searchCache[query.lowercase()] = report
        memoryAgent.save(
            content = "Research on '$query': $summary",
            type = MemoryAgent.MemoryType.LONG_TERM,
            tags = listOf("research", "internet") + query.split(" ").take(3),
            importance = 0.7f
        )

        return report
    }

    // ── Internet search (DuckDuckGo Instant Answer API — no key needed) ─────

    private suspend fun searchInternet(query: String, maxSources: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()

            // 1. DuckDuckGo Instant Answer
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "AladdinAssistant/1.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val abstract = json.optString("Abstract")
                    val abstractUrl = json.optString("AbstractURL")
                    val abstractSource = json.optString("AbstractSource")
                    if (abstract.isNotBlank()) {
                        results.add(
                            SearchResult(
                                title = abstractSource,
                                url = abstractUrl,
                                snippet = abstract,
                                source = abstractSource,
                                credibilityScore = scoreSource(abstractUrl),
                                isVerified = true
                            )
                        )
                    }
                    // Related topics
                    val related = json.optJSONArray("RelatedTopics")
                    related?.let {
                        for (i in 0 until minOf(it.length(), maxSources)) {
                            val topic = it.optJSONObject(i) ?: continue
                            val text = topic.optString("Text")
                            val firstUrl = topic.optString("FirstURL")
                            if (text.isNotBlank()) {
                                results.add(
                                    SearchResult(
                                        title = text.take(80),
                                        url = firstUrl,
                                        snippet = text,
                                        source = extractDomain(firstUrl),
                                        credibilityScore = scoreSource(firstUrl)
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DuckDuckGo search failed: ${e.message}")
            }

            // 2. Wikipedia summary (always try)
            try {
                val wikiQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$wikiQuery"
                val request = Request.Builder().url(wikiUrl)
                    .header("User-Agent", "AladdinAssistant/1.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val extract = json.optString("extract")
                    val title = json.optString("title")
                    val pageUrl = json.optJSONObject("content_urls")
                        ?.optJSONObject("desktop")?.optString("page") ?: ""
                    if (extract.isNotBlank()) {
                        results.add(
                            SearchResult(
                                title = title,
                                url = pageUrl,
                                snippet = extract.take(500),
                                source = "Wikipedia",
                                credibilityScore = 0.85f,
                                isVerified = true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wikipedia search failed: ${e.message}")
            }

            results.take(maxSources)
        }

    // ── Post-processing ─────────────────────────────────────────────────────

    private fun filterAndDeduplicate(results: List<SearchResult>): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val key = result.snippet.take(100).lowercase().replace("\\s+".toRegex(), "")
            seen.add(key) // returns false if already present
        }.filter { it.snippet.length > 30 }
    }

    private fun rankByCredibility(results: List<SearchResult>): List<SearchResult> =
        results.sortedByDescending { it.credibilityScore }

    private fun summarise(query: String, sources: List<SearchResult>): String {
        if (sources.isEmpty()) return "No information found for '$query'."
        val topSnippets = sources.take(3).joinToString(" ") { it.snippet.take(200) }
        // Naive extractive summary: first 2 sentences
        val sentences = topSnippets.split(Regex("[.!?]")).filter { it.trim().length > 20 }
        return sentences.take(3).joinToString(". ").trim() + "."
    }

    private fun extractKeyFacts(sources: List<SearchResult>): List<String> {
        return sources.flatMap { result ->
            result.snippet.split(Regex("[.!?]"))
                .filter { it.trim().length in 20..200 }
                .take(2)
                .map { it.trim() }
        }.distinct().take(8)
    }

    private fun scoreSource(url: String): Float {
        val domain = extractDomain(url)
        return DOMAIN_CREDIBILITY.entries
            .firstOrNull { (key, _) -> domain.contains(key) }?.value ?: 0.55f
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host.removePrefix("www.")
        } catch (e: Exception) { url }
    }

    // ── Message handler ─────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        if (msg.type != AgentCommunication.MessageType.TASK_REQUEST) return
        val query = msg.payload["query"]?.toString() ?: return
        val report = research(query)
        AgentCommunication.reportResult(
            sender = AgentCommunication.AgentType.RESEARCH,
            receiver = msg.sender,
            taskId = msg.taskId,
            result = mapOf(
                "summary" to report.summary,
                "facts" to report.keyFacts,
                "confidence" to report.confidence,
                "sourceCount" to report.sources.size
            )
        )
    }
}
