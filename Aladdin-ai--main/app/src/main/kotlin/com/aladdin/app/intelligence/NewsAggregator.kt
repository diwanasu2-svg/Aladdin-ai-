package com.aladdin.app.intelligence

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

// ─── Phase 11 Item 3: Real News Aggregation — RSS + NewsAPI + dedup ───────────

@Singleton
class NewsAggregator @Inject constructor() {
    companion object {
        private const val TAG = "NewsAggregator"
        // Publicly accessible RSS JSON feeds (no key required)
        private val RSS_FEEDS = mapOf(
            "BBC"       to "https://feeds.bbci.co.uk/news/rss.xml",
            "Reuters"   to "https://feeds.reuters.com/reuters/topNews",
            "TechCrunch"to "https://techcrunch.com/feed/",
            "Ars"       to "https://feeds.arstechnica.com/arstechnica/index"
        )
        private const val NEWS_API = "https://newsapi.org/v2/top-headlines"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class NewsArticle(
        val title: String,
        val source: String,
        val url: String,
        val publishedAt: String,
        val summary: String = ""
    )

    // ── Fetch from multiple sources in parallel ───────────────────────────────
    suspend fun fetchTopHeadlines(
        apiKey: String = "",
        category: String = "general",
        maxArticles: Int = 20
    ): List<NewsArticle> = withContext(Dispatchers.IO) {
        val allArticles = mutableListOf<NewsArticle>()

        // Try NewsAPI first if key provided
        if (apiKey.isNotBlank()) {
            try {
                val articles = fetchNewsApi(apiKey, category)
                allArticles.addAll(articles)
                Log.i(TAG, "NewsAPI returned ${articles.size} articles")
            } catch (e: Exception) {
                Log.w(TAG, "NewsAPI failed: ${e.message} — falling back to RSS")
            }
        }

        // Fetch RSS feeds in parallel as fallback or supplement
        val rssDeferreds = RSS_FEEDS.map { (name, url) ->
            async {
                try {
                    fetchRssFeed(url, name)
                } catch (e: Exception) {
                    Log.w(TAG, "RSS $name failed: ${e.message}")
                    emptyList()
                }
            }
        }
        val rssResults = rssDeferreds.awaitAll().flatten()
        allArticles.addAll(rssResults)

        // Deduplicate by title similarity
        val deduped = deduplicateArticles(allArticles)
        Log.i(TAG, "Total after dedup: ${deduped.size} articles from ${allArticles.size}")
        deduped.take(maxArticles)
    }

    // ── Fetch from NewsAPI.org ────────────────────────────────────────────────
    private fun fetchNewsApi(apiKey: String, category: String): List<NewsArticle> {
        val url = "$NEWS_API?country=us&category=$category&pageSize=20&apiKey=$apiKey"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val arts = json.optJSONArray("articles") ?: return emptyList()
            return (0 until arts.length()).map { i ->
                val a = arts.getJSONObject(i)
                NewsArticle(
                    title       = a.optString("title", "Untitled"),
                    source      = a.optJSONObject("source")?.optString("name") ?: "NewsAPI",
                    url         = a.optString("url", ""),
                    publishedAt = a.optString("publishedAt", ""),
                    summary     = a.optString("description", "")
                )
            }.filter { it.title.isNotBlank() && it.title != "[Removed]" }
        }
    }

    // ── Parse RSS XML feed ────────────────────────────────────────────────────
    private fun fetchRssFeed(feedUrl: String, sourceName: String): List<NewsArticle> {
        val req = Request.Builder().url(feedUrl)
            .header("User-Agent", "AladdinAI/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val xml = resp.body?.string() ?: return emptyList()
            return parseRssXml(xml, sourceName)
        }
    }

    private fun parseRssXml(xml: String, source: String): List<NewsArticle> {
        val articles = mutableListOf<NewsArticle>()
        val titleRegex   = Regex("<title>(?:<!\\[CDATA\\[)?(.+?)(?:]]>)?</title>", RegexOption.DOT_MATCHES_ALL)
        val linkRegex    = Regex("<link>(.+?)</link>", RegexOption.DOT_MATCHES_ALL)
        val descRegex    = Regex("<description>(?:<!\\[CDATA\\[)?(.+?)(?:]]>)?</description>", RegexOption.DOT_MATCHES_ALL)
        val pubDateRegex = Regex("<pubDate>(.+?)</pubDate>")

        val titles   = titleRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()
        val links    = linkRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()
        val descs    = descRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()
        val dates    = pubDateRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()

        // Skip first title/link (usually channel info)
        for (i in 1 until minOf(titles.size, 10)) {
            val title = titles.getOrNull(i)?.let { stripHtml(it) } ?: continue
            if (title.isBlank() || title.length < 5) continue
            articles.add(NewsArticle(
                title       = title,
                source      = source,
                url         = links.getOrNull(i) ?: "",
                publishedAt = dates.getOrNull(i - 1) ?: "",
                summary     = descs.getOrNull(i)?.let { stripHtml(it).take(200) } ?: ""
            ))
        }
        return articles
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
         .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
         .replace("&#39;", "'").trim()

    // ── Deduplicate by title similarity (Jaccard on word sets) ───────────────
    private fun deduplicateArticles(articles: List<NewsArticle>): List<NewsArticle> {
        val seen = mutableListOf<NewsArticle>()
        for (a in articles) {
            val wordsA = a.title.lowercase().split(" ").toSet()
            val isDup = seen.any { b ->
                val wordsB = b.title.lowercase().split(" ").toSet()
                val inter  = wordsA.intersect(wordsB).size.toDouble()
                val union  = wordsA.union(wordsB).size.toDouble()
                if (union > 0) (inter / union) > 0.6 else false
            }
            if (!isDup) seen.add(a)
        }
        return seen
    }

    // ── Summarize headlines as a voice-friendly string ────────────────────────
    fun toVoiceSummary(articles: List<NewsArticle>, maxItems: Int = 5): String {
        if (articles.isEmpty()) return "No news articles available right now."
        val sb = StringBuilder("Here are today's top headlines. ")
        articles.take(maxItems).forEachIndexed { i, a ->
            sb.append("${i + 1}. ${a.title} — from ${a.source}. ")
        }
        return sb.toString()
    }
}
