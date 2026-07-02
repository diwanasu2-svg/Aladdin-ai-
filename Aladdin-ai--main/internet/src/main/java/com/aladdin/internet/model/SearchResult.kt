package com.aladdin.internet.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val snippet: String,
    val url: String,
    val source: SearchSource,
    val type: SearchType,
    val imageUrl: String? = null,
    val publishedAt: String? = null,
    val relevanceScore: Float = 0f,
    val authorityScore: Float = 0f,
    val finalScore: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class RankedResult(
    val result: SearchResult,
    val rank: Int,
    val explanation: String = ""
)

@Serializable
data class SearchResponse(
    val query: String,
    val type: SearchType,
    val results: List<RankedResult>,
    val summary: String? = null,
    val fromCache: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val sources: List<SourceCitation> = emptyList()
)

@Serializable
data class SourceCitation(
    val title: String,
    val url: String,
    val source: SearchSource,
    val accessedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class SearchSource {
    DUCKDUCKGO,
    WIKIPEDIA,
    NEWS_API,
    BRAVE_SEARCH,
    SERPER,
    CACHE
}
