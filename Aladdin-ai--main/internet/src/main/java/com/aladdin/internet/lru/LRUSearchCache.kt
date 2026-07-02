package com.aladdin.internet.lru

import com.aladdin.internet.model.SearchResponse
import java.util.LinkedHashMap

/**
 * Thread-safe in-memory LRU cache for search results.
 * Holds up to 100 query responses.
 */
class LRUSearchCache(private val maxSize: Int = 100) {

    private val lock = Any()

    private val cache = object : LinkedHashMap<String, SearchResponse>(
        (maxSize / 0.75f).toInt() + 1, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, SearchResponse>): Boolean {
            return size > maxSize
        }
    }

    fun get(key: String): SearchResponse? = synchronized(lock) {
        cache[normalizeKey(key)]
    }

    fun put(key: String, response: SearchResponse) = synchronized(lock) {
        cache[normalizeKey(key)] = response
    }

    fun remove(key: String) = synchronized(lock) {
        cache.remove(normalizeKey(key))
    }

    fun clear() = synchronized(lock) {
        cache.clear()
    }

    fun size(): Int = synchronized(lock) { cache.size }

    fun contains(key: String): Boolean = synchronized(lock) {
        cache.containsKey(normalizeKey(key))
    }

    fun getAllKeys(): List<String> = synchronized(lock) {
        cache.keys.toList()
    }

    private fun normalizeKey(key: String): String = key.trim().lowercase()
}
