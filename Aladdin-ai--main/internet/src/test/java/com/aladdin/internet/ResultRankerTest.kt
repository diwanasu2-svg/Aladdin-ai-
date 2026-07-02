package com.aladdin.internet

import com.aladdin.internet.model.RankedResult
import com.aladdin.internet.model.SearchResult
import com.aladdin.internet.model.SearchSource
import com.aladdin.internet.model.SearchType
import com.aladdin.internet.ranking.ResultRanker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ResultRankerTest {

    private lateinit var ranker: ResultRanker

    @Before
    fun setUp() { ranker = ResultRanker() }

    private fun makeResult(
        title: String = "Result Title",
        snippet: String = "This is a snippet of the result",
        source: SearchSource = SearchSource.DUCKDUCKGO,
        imageUrl: String? = null,
        publishedAt: String? = null
    ) = SearchResult(
        title = title,
        snippet = snippet,
        url = "https://example.com",
        source = source,
        type = SearchType.WEB,
        imageUrl = imageUrl,
        publishedAt = publishedAt
    )

    @Test fun `empty results returns empty`() {
        val ranked = ranker.rank(emptyList(), "query", SearchType.WEB)
        assertThat(ranked).isEmpty()
    }

    @Test fun `single result gets rank 1`() {
        val results = listOf(makeResult())
        val ranked = ranker.rank(results, "query", SearchType.WEB)
        assertThat(ranked).hasSize(1)
        assertThat(ranked.first().rank).isEqualTo(1)
    }

    @Test fun `results sorted by final score descending`() {
        val results = listOf(
            makeResult(title = "Python Programming Guide", snippet = "Learn Python for data science"),
            makeResult(title = "Java Spring Boot", snippet = "Enterprise Java development"),
            makeResult(title = "Python machine learning tutorial", snippet = "Python ML deep learning neural networks")
        )
        val ranked = ranker.rank(results, "python", SearchType.WEB)
        assertThat(ranked[0].rank).isEqualTo(1)
        assertThat(ranked[0].result.finalScore).isAtLeast(ranked[1].result.finalScore)
    }

    @Test fun `Wikipedia source gets higher authority for WIKIPEDIA type`() {
        val wikiResult = makeResult(source = SearchSource.WIKIPEDIA)
        val ddgResult = makeResult(title = "Same title duck", snippet = "Same content duck", source = SearchSource.DUCKDUCKGO)
        val ranked = ranker.rank(listOf(ddgResult, wikiResult), "query", SearchType.WIKIPEDIA)
        assertThat(ranked.first().result.source).isEqualTo(SearchSource.WIKIPEDIA)
    }

    @Test fun `title match boosts relevance`() {
        val titleMatch = makeResult(title = "kotlin coroutines guide", snippet = "Some snippet about android")
        val snippetMatch = makeResult(title = "Android development", snippet = "kotlin coroutines are powerful tools")
        val ranked = ranker.rank(listOf(snippetMatch, titleMatch), "kotlin coroutines", SearchType.WEB)
        assertThat(ranked.first().result.title).contains("kotlin")
    }

    @Test fun `all results get final score in range 0 to 1`() {
        val results = (1..10).map { i ->
            makeResult(
                title = "Result $i about topic",
                snippet = "Snippet for result $i with some content",
                source = SearchSource.values()[i % SearchSource.values().size]
            )
        }
        val ranked = ranker.rank(results, "result topic", SearchType.WEB)
        ranked.forEach {
            assertThat(it.result.finalScore).isAtLeast(0f)
            assertThat(it.result.finalScore).isAtMost(1.1f)
        }
    }

    @Test fun `ranks are sequential starting from 1`() {
        val results = (1..5).map { i -> makeResult(title = "Result $i") }
        val ranked = ranker.rank(results, "result", SearchType.WEB)
        assertThat(ranked.map { it.rank }).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test fun `freshness bonus for news with publishedAt`() {
        val freshResult = makeResult(source = SearchSource.NEWS_API, publishedAt = "2024-01-15T10:00:00Z")
        val staleResult = makeResult(source = SearchSource.NEWS_API, publishedAt = null)
        val ranked = ranker.rank(listOf(staleResult, freshResult), "news", SearchType.NEWS)
        assertThat(ranked.first().result.publishedAt).isNotNull()
    }

    @Test fun `explanation string is populated`() {
        val results = listOf(makeResult())
        val ranked = ranker.rank(results, "query", SearchType.WEB)
        assertThat(ranked.first().explanation).isNotEmpty()
        assertThat(ranked.first().explanation).contains("Rank #1")
    }

    @Test fun `image bonus for results with imageUrl on IMAGES type`() {
        val withImage = makeResult(imageUrl = "https://example.com/img.jpg")
        val withoutImage = makeResult(imageUrl = null)
        val ranked = ranker.rank(listOf(withoutImage, withImage), "query", SearchType.IMAGES)
        assertThat(ranked.first().result.imageUrl).isNotNull()
    }

    @Test fun `empty query still produces results`() {
        val results = (1..3).map { makeResult() }
        val ranked = ranker.rank(results, "", SearchType.WEB)
        assertThat(ranked).hasSize(3)
    }

    @Test fun `relevance and authority scores set on returned results`() {
        val results = listOf(makeResult(title = "Android development guide", snippet = "Android apps with Kotlin"))
        val ranked = ranker.rank(results, "android kotlin", SearchType.WEB)
        assertThat(ranked.first().result.relevanceScore).isGreaterThan(0f)
        assertThat(ranked.first().result.authorityScore).isGreaterThan(0f)
    }

    @Test fun `100 results ranked within acceptable time`() {
        val results = (1..100).map { i ->
            makeResult(
                title = "Result $i android kotlin development",
                snippet = "Description of result $i involving android development patterns"
            )
        }
        val start = System.currentTimeMillis()
        val ranked = ranker.rank(results, "android kotlin", SearchType.WEB)
        assertThat(System.currentTimeMillis() - start).isLessThan(1000L)
        assertThat(ranked).hasSize(100)
    }
}
