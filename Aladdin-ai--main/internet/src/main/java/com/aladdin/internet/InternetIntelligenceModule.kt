package com.aladdin.internet

import android.content.Context
import com.aladdin.internet.model.SearchResponse
import com.aladdin.internet.model.SearchType
import com.aladdin.internet.service.BackgroundSyncWorker
import com.aladdin.internet.service.InternetIntelligenceConfig
import com.aladdin.internet.service.InternetSearchService
import com.aladdin.internet.summarizer.LLMBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 *  Aladdin AI — Internet Intelligence Module
 *
 *  One-stop entry point for all internet search capabilities:
 *   • DuckDuckGo Instant Answer API (free, no key)
 *   • Wikipedia article extraction (free, no key)
 *   • NewsAPI.org (requires free key)
 *   • Brave Search API (requires key)
 *   • Google via Serper.dev (optional key)
 *
 *  Features:
 *   • Auto-detect query type (web / news / wiki / definition / images …)
 *   • LRU in-memory cache — 100 queries, instant retrieval
 *   • Room DB cache — offline fallback, 6-hour TTL
 *   • Result ranking — Relevance + Authority composite score
 *   • Source citations with URLs
 *   • Extractive / LLM summarization
 *   • WorkManager background cache cleanup
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * Quick-start:
 *
 *   InternetIntelligenceModule.init(context,
 *       InternetIntelligenceConfig(
 *           newsApiKey  = BuildConfig.NEWS_API_KEY,
 *           braveApiKey = BuildConfig.BRAVE_API_KEY,
 *           serperApiKey = BuildConfig.SERPER_API_KEY  // optional
 *       )
 *   )
 *
 *   // In a coroutine:
 *   val result = InternetIntelligenceModule.search("Latest AI news", SearchType.NEWS)
 *   println(result.summary)
 *   result.results.forEach { println("${it.rank}. ${it.result.title} — ${it.result.url}") }
 */
object InternetIntelligenceModule {

    private lateinit var service: InternetSearchService
    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Must be called once — e.g. from Application.onCreate(). */
    fun init(context: Context, config: InternetIntelligenceConfig = InternetIntelligenceConfig()) {
        service = InternetSearchService(context.applicationContext, config)
        BackgroundSyncWorker.schedule(context)
    }

    // ── Core search ────────────────────────────────────────────────────────

    /**
     * Auto-detect query intent and route to the best sources.
     * Blocks until results are ready; call from a coroutine.
     */
    suspend fun autoSearch(query: String): SearchResponse = service.autoSearch(query)

    /**
     * Explicit search with a specific [SearchType].
     */
    suspend fun search(query: String, type: SearchType): SearchResponse =
        service.search(query, type)

    // ── Convenience helpers ────────────────────────────────────────────────

    suspend fun webSearch(query: String)        = search(query, SearchType.WEB)
    suspend fun newsSearch(query: String)       = search(query, SearchType.NEWS)
    suspend fun wikiSearch(query: String)       = search(query, SearchType.WIKIPEDIA)
    suspend fun imageSearch(query: String)      = search(query, SearchType.IMAGES)
    suspend fun defineWord(word: String)        = search(word, SearchType.DEFINITION)
    suspend fun weatherSearch(location: String) = search(location, SearchType.WEATHER)

    // ── Cache management ───────────────────────────────────────────────────

    /** Clear in-memory LRU cache only. */
    fun clearMemoryCache() = service.clearMemoryCache()

    /** Clear both in-memory and Room caches (suspend). */
    suspend fun clearAllCaches() {
        service.clearMemoryCache()
        service.clearPersistentCache()
    }

    /** Fire-and-forget cache wipe from non-coroutine context. */
    fun clearAllCachesAsync() {
        moduleScope.launch { clearAllCaches() }
    }

    // ── Background sync ────────────────────────────────────────────────────

    fun scheduleBackgroundSync(context: Context) = BackgroundSyncWorker.schedule(context)
    fun runBackgroundSyncNow(context: Context)   = BackgroundSyncWorker.runOnce(context)
    fun cancelBackgroundSync(context: Context)   = BackgroundSyncWorker.cancel(context)
}
