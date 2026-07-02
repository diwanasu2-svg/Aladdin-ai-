package com.aladdin.internet.service

import com.aladdin.internet.summarizer.LLMBackend

/**
 * Configuration for the Internet Intelligence Module.
 *
 * @param newsApiKey          API key from newsapi.org (free tier available). Enables news search.
 * @param braveApiKey         API key from api.search.brave.com. Enables Brave web/image/news search.
 * @param serperApiKey        API key from serper.dev (Google Search wrapper). Optional.
 * @param enableSummarization Whether to generate an LLM summary for each search response.
 * @param llmBackend          Custom LLM backend for summarization. Defaults to extractive summarizer.
 * @param cacheMaxMemory      Max entries in LRU memory cache (default 100).
 * @param cacheTtlMs          Room DB cache TTL in milliseconds (default 6 hours).
 */
data class InternetIntelligenceConfig(
    val newsApiKey:           String?     = null,
    val braveApiKey:          String?     = null,
    val serperApiKey:         String?     = null,
    val enableSummarization:  Boolean     = true,
    val llmBackend:           LLMBackend? = null,
    val cacheMaxMemory:       Int         = 100,
    val cacheTtlMs:           Long        = 6 * 60 * 60 * 1000L
)
