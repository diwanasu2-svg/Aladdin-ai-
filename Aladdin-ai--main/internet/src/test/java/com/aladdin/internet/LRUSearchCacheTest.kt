package com.aladdin.internet

import com.aladdin.internet.lru.LRUSearchCache
import com.aladdin.internet.model.RankedResult
import com.aladdin.internet.model.SearchResponse
import com.aladdin.internet.model.SearchResult
import com.aladdin.internet.model.SearchSource
import com.aladdin.internet.model.SearchType
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class LRUSearchCacheTest {

    private lateinit var cache: LRUSearchCache

    @Before
    fun setUp() { cache = LRUSearchCache(maxSize = 5) }

    private fun makeResponse(query: String) = SearchResponse(
        query = query,
        type = SearchType.WEB,
        results = emptyList(),
        timestamp = System.currentTimeMillis()
    )

    @Test fun `get returns null for missing key`() {
        assertThat(cache.get("nonexistent")).isNull()
    }

    @Test fun `put and get round-trip`() {
        val response = makeResponse("kotlin android")
        cache.put("kotlin android", response)
        assertThat(cache.get("kotlin android")).isEqualTo(response)
    }

    @Test fun `keys are normalized to lowercase`() {
        val response = makeResponse("kotlin")
        cache.put("KOTLIN", response)
        assertThat(cache.get("kotlin")).isEqualTo(response)
        assertThat(cache.get("KOTLIN")).isEqualTo(response)
    }

    @Test fun `keys are trimmed`() {
        val response = makeResponse("kotlin")
        cache.put("  kotlin  ", response)
        assertThat(cache.get("kotlin")).isEqualTo(response)
    }

    @Test fun `LRU eviction when capacity exceeded`() {
        val cache = LRUSearchCache(maxSize = 3)
        cache.put("a", makeResponse("a"))
        cache.put("b", makeResponse("b"))
        cache.put("c", makeResponse("c"))
        cache.get("a")  // access "a" to make it recently used
        cache.put("d", makeResponse("d"))  // "b" should be evicted
        assertThat(cache.contains("b")).isFalse()
        assertThat(cache.contains("a")).isTrue()
        assertThat(cache.contains("c")).isTrue()
        assertThat(cache.contains("d")).isTrue()
    }

    @Test fun `size reflects number of entries`() {
        assertThat(cache.size()).isEqualTo(0)
        cache.put("key1", makeResponse("q1"))
        assertThat(cache.size()).isEqualTo(1)
    }

    @Test fun `remove deletes entry`() {
        cache.put("target", makeResponse("target"))
        cache.remove("target")
        assertThat(cache.contains("target")).isFalse()
    }

    @Test fun `clear removes all entries`() {
        cache.put("a", makeResponse("a"))
        cache.put("b", makeResponse("b"))
        cache.clear()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test fun `contains returns true for existing key`() {
        cache.put("hello", makeResponse("hello"))
        assertThat(cache.contains("hello")).isTrue()
    }

    @Test fun `update existing key`() {
        val original = makeResponse("q1")
        val updated = makeResponse("q1-updated")
        cache.put("key", original)
        cache.put("key", updated)
        assertThat(cache.get("key")).isEqualTo(updated)
        assertThat(cache.size()).isEqualTo(1)
    }

    @Test fun `fromCache flag propagated`() {
        val response = SearchResponse(
            query = "test", type = SearchType.WEB,
            results = emptyList(), fromCache = true
        )
        cache.put("test", response)
        val retrieved = cache.get("test")
        assertThat(retrieved?.fromCache).isTrue()
    }

    @Test fun `thread safety - concurrent puts do not throw`() {
        val threads = (1..10).map { i ->
            Thread { cache.put("key$i", makeResponse("query$i")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }
        assertThat(cache.size()).isAtMost(5)
    }
}
