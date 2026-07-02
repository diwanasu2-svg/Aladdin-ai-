package com.aladdin.internet.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aladdin.internet.api.*
import com.aladdin.internet.cache.SearchCacheDatabase
import com.aladdin.internet.cache.SearchCacheEntity
import com.aladdin.internet.lru.LRUSearchCache
import com.aladdin.internet.model.*
import com.aladdin.internet.ranking.ResultRanker
import com.aladdin.internet.summarizer.LLMSummarizer
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

class InternetSearchService(
    private val context: Context,
    private val config: InternetIntelligenceConfig
) {
    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson       = Gson()
    private val json       = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ranker     = ResultRanker()
    private val summarizer = LLMSummarizer(config.llmBackend)

    /** LRU in-memory cache — 100 slots */
    private val memCache = LRUSearchCache(maxSize = config.cacheMaxMemory)

    /** Room persistent cache */
    private val db  = SearchCacheDatabase.getInstance(context)
    private val dao = db.searchCacheDao()

    /* Lazy API clients — only built when a key is present */
    private val duckDuckGo by lazy { RetrofitClient.duckDuckGoApi() }
    private val wikipedia  by lazy { RetrofitClient.wikipediaApi() }
    private val newsApi    by lazy { config.newsApiKey?.let  { RetrofitClient.newsApi(it) } }
    private val brave      by lazy { config.braveApiKey?.let { RetrofitClient.braveSearchApi(it) } }
    private val serper     by lazy { config.serperApiKey?.let{ RetrofitClient.serperApi(it) } }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Auto-detect query intent and route to the best sources. */
    suspend fun autoSearch(query: String): SearchResponse {
        val type = detectQueryType(query)
        return search(query, type)
    }

    /** Explicit search with a specific [SearchType]. */
    suspend fun search(query: String, type: SearchType): SearchResponse {
        val cacheKey = "${type.name}:${query.trim().lowercase()}"

        // 1. LRU memory cache (instant)
        memCache.get(cacheKey)?.let { return it }

        // 2. Offline fallback — any Room entry, even expired
        if (!isNetworkAvailable()) {
            val persisted = dao.getAny(cacheKey)
            if (persisted != null) {
                // Fix 13: use kotlinx.serialization instead of Gson for deserialization
                val response = runCatching {
                    json.decodeFromString<SearchResponse>(persisted.responseJson).copy(fromCache = true)
                }.getOrNull()
                if (response != null) {
                    memCache.put(cacheKey, response)
                    return response
                }
            }
            return SearchResponse(query, type, emptyList(),
                "Offline — no cached results for: $query", fromCache = true)
        }

        // 3. Fresh fetch
        val rawResults = fetchResults(query, type)
        val ranked     = ranker.rank(rawResults, query, type)
        val summary    = if (config.enableSummarization && ranked.isNotEmpty())
                             summarizer.summarize(query, ranked, type) else null
        val citations  = ranked.take(5).map { r ->
            SourceCitation(r.result.title, r.result.url, r.result.source)
        }

        val response = SearchResponse(
            query   = query,
            type    = type,
            results = ranked,
            summary = summary,
            sources = citations
        )

        // 4. Cache in both layers
        memCache.put(cacheKey, response)
        scope.launch { persistToRoom(cacheKey, query, type, response) }
        return response
    }

    // ── Source fan-out ────────────────────────────────────────────────────

    private suspend fun fetchResults(query: String, type: SearchType): List<SearchResult> =
        coroutineScope {
            val jobs = mutableListOf<Deferred<List<SearchResult>>>()
            when (type) {
                SearchType.WIKIPEDIA  -> { jobs += async { fetchWikipedia(query) } }
                SearchType.NEWS       -> {
                    config.newsApiKey?.let   { jobs += async { fetchNews(query) } }
                    config.braveApiKey?.let  { jobs += async { fetchBraveNews(query) } }
                    config.serperApiKey?.let { jobs += async { fetchSerperNews(query) } }
                    if (jobs.isEmpty()) jobs += async { fetchDuckDuckGo(query) }
                }
                SearchType.IMAGES     -> {
                    config.braveApiKey?.let  { jobs += async { fetchBraveImages(query) } }
                    config.serperApiKey?.let { jobs += async { fetchSerperImages(query) } }
                }
                SearchType.DEFINITION -> {
                    jobs += async { fetchDuckDuckGo(query) }
                    jobs += async { fetchWikipedia(query) }
                }
                else -> {   // WEB / CALCULATION / WEATHER / general
                    jobs += async { fetchDuckDuckGo(query) }
                    config.braveApiKey?.let  { jobs += async { fetchBraveWeb(query) } }
                    config.serperApiKey?.let { jobs += async { fetchSerperWeb(query) } }
                }
            }
            jobs.awaitAll().flatten().distinctBy { it.url }
        }

    // ── DuckDuckGo ────────────────────────────────────────────────────────

    private suspend fun fetchDuckDuckGo(query: String): List<SearchResult> = runCatching {
        val resp    = duckDuckGo.instantAnswer(query)
        val results = mutableListOf<SearchResult>()

        resp.answer?.takeIf { it.isNotBlank() }?.let {
            results += SearchResult(
                title   = resp.heading ?: query,
                snippet = it,
                url     = resp.abstractUrl ?: "https://duckduckgo.com/?q=${query.encode()}",
                source  = SearchSource.DUCKDUCKGO,
                type    = SearchType.WEB,
                imageUrl = resp.image?.takeIf { i -> i.isNotBlank() }
            )
        }
        resp.abstractText?.takeIf { it.isNotBlank() }?.let {
            results += SearchResult(
                title   = resp.heading ?: "DuckDuckGo: $query",
                snippet = it,
                url     = resp.abstractUrl ?: "https://duckduckgo.com/?q=${query.encode()}",
                source  = SearchSource.DUCKDUCKGO,
                type    = SearchType.WEB
            )
        }
        resp.relatedTopics?.take(5)?.forEach { t ->
            if (!t.text.isNullOrBlank() && !t.firstUrl.isNullOrBlank()) {
                results += SearchResult(
                    title   = t.text.take(80),
                    snippet = t.text,
                    url     = t.firstUrl,
                    source  = SearchSource.DUCKDUCKGO,
                    type    = SearchType.WEB
                )
            }
        }
        results
    }.getOrElse { emptyList() }

    // ── Wikipedia ─────────────────────────────────────────────────────────

    private suspend fun fetchWikipedia(query: String): List<SearchResult> = runCatching {
        val hits = wikipedia.search(query = query, limit = 5).query?.search ?: return@runCatching emptyList()
        hits.mapNotNull { hit ->
            val page = runCatching {
                wikipedia.getArticle(title = hit.title).query?.pages?.values?.firstOrNull()
            }.getOrNull()
            val extract = page?.extract?.take(500)
                ?: hit.snippet.replace(Regex("<.*?>"), "")
            val url     = page?.fullUrl
                ?: "https://en.wikipedia.org/wiki/${hit.title.replace(" ", "_")}"
            SearchResult(
                title    = hit.title,
                snippet  = extract,
                url      = url,
                source   = SearchSource.WIKIPEDIA,
                type     = SearchType.WIKIPEDIA,
                imageUrl = page?.thumbnail?.source
            )
        }
    }.getOrElse { emptyList() }

    // ── NewsAPI ───────────────────────────────────────────────────────────

    private suspend fun fetchNews(query: String): List<SearchResult> = runCatching {
        val api = newsApi ?: return@runCatching emptyList()
        api.search(query).articles.map { a ->
            SearchResult(
                title       = a.title,
                snippet     = a.description ?: a.content?.take(200) ?: "",
                url         = a.url,
                source      = SearchSource.NEWS_API,
                type        = SearchType.NEWS,
                imageUrl    = a.urlToImage,
                publishedAt = a.publishedAt
            )
        }
    }.getOrElse { emptyList() }

    // ── Brave ─────────────────────────────────────────────────────────────

    private suspend fun fetchBraveWeb(query: String): List<SearchResult> = runCatching {
        brave?.webSearch(query)?.web?.results?.map { r ->
            SearchResult(title = r.title, snippet = r.description ?: "", url = r.url,
                source = SearchSource.BRAVE_SEARCH, type = SearchType.WEB)
        } ?: emptyList()
    }.getOrElse { emptyList() }

    private suspend fun fetchBraveNews(query: String): List<SearchResult> = runCatching {
        brave?.webSearch(query)?.news?.results?.map { r ->
            SearchResult(title = r.title, snippet = r.description ?: "", url = r.url,
                source = SearchSource.BRAVE_SEARCH, type = SearchType.NEWS,
                imageUrl = r.thumbnail?.src, publishedAt = r.age)
        } ?: emptyList()
    }.getOrElse { emptyList() }

    private suspend fun fetchBraveImages(query: String): List<SearchResult> = runCatching {
        brave?.imageSearch(query)?.results?.map { r ->
            SearchResult(title = r.title, snippet = r.source ?: "", url = r.url,
                source = SearchSource.BRAVE_SEARCH, type = SearchType.IMAGES,
                imageUrl = r.thumbnail?.src)
        } ?: emptyList()
    }.getOrElse { emptyList() }

    // ── Serper (Google) ───────────────────────────────────────────────────

    private suspend fun fetchSerperWeb(query: String): List<SearchResult> = runCatching {
        val api  = serper ?: return@runCatching emptyList()
        val resp = api.search(SerperRequest(query))
        val out  = mutableListOf<SearchResult>()
        resp.answerBox?.let { ab ->
            out += SearchResult(title = ab.title ?: query,
                snippet = ab.answer ?: ab.snippet ?: "",
                url = ab.link ?: "https://google.com/search?q=${query.encode()}",
                source = SearchSource.SERPER, type = SearchType.WEB)
        }
        resp.organic?.forEach { r ->
            out += SearchResult(title = r.title, snippet = r.snippet ?: "", url = r.link,
                source = SearchSource.SERPER, type = SearchType.WEB, publishedAt = r.date)
        }
        out
    }.getOrElse { emptyList() }

    private suspend fun fetchSerperImages(query: String): List<SearchResult> = runCatching {
        serper?.imageSearch(SerperRequest(query))?.images?.map { img ->
            SearchResult(title = img.title, snippet = img.link, url = img.link,
                source = SearchSource.SERPER, type = SearchType.IMAGES, imageUrl = img.imageUrl)
        } ?: emptyList()
    }.getOrElse { emptyList() }

    private suspend fun fetchSerperNews(query: String): List<SearchResult> = runCatching {
        serper?.newsSearch(SerperRequest(query))?.news?.map { n ->
            SearchResult(title = n.title, snippet = n.snippet ?: "", url = n.link,
                source = SearchSource.SERPER, type = SearchType.NEWS,
                imageUrl = n.imageUrl, publishedAt = n.date)
        } ?: emptyList()
    }.getOrElse { emptyList() }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun detectQueryType(query: String): SearchType {
        val q = query.trim().lowercase()
        return when {
            Regex("\\b(news|latest|today|breaking|current|update)\\b").containsMatchIn(q) -> SearchType.NEWS
            Regex("\\b(who is|what is|define|definition|meaning of)\\b").containsMatchIn(q) -> SearchType.DEFINITION
            Regex("\\b(weather|temperature|forecast|humidity|rain|snow)\\b").containsMatchIn(q) -> SearchType.WEATHER
            Regex("\\b(image|photo|picture|photo of|pic of)\\b").containsMatchIn(q) -> SearchType.IMAGES
            Regex("\\b(calculate|compute|how much is|\\d+\\s*[+\\-*/]\\s*\\d+)\\b").containsMatchIn(q) -> SearchType.CALCULATION
            Regex("\\b(wikipedia|wiki|article|history of|biography)\\b").containsMatchIn(q) -> SearchType.WIKIPEDIA
            else -> SearchType.WEB
        }
    }

    // Fix 13: serialize with kotlinx.serialization for consistency
    private suspend fun persistToRoom(
        key: String, query: String, type: SearchType, response: SearchResponse
    ) = runCatching {
        val count = dao.count()
        if (count >= 500) {
            val oldest = dao.getOldest(count - 490)
            dao.deleteByKeys(oldest.map { it.queryKey })
        }
        dao.deleteExpired()
        dao.insert(SearchCacheEntity(
            queryKey     = key,
            query        = query,
            searchType   = type.name,
            responseJson = json.encodeToString(SearchResponse.serializer(), response),
            expiresAt    = System.currentTimeMillis() + config.cacheTtlMs
        ))
    }

    private fun isNetworkAvailable(): Boolean {
        val cm  = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    fun clearMemoryCache()             = memCache.clear()
    suspend fun clearPersistentCache() = dao.clearAll()
}
