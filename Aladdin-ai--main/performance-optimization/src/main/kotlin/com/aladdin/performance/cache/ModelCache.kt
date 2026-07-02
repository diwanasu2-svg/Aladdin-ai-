package com.aladdin.performance.cache

import java.io.File

/** Lifecycle state of a cached model */
enum class CacheState { NOT_LOADED, LOADING, LOADED, EVICTED, ERROR }

/** Metadata attached to every cached entry */
data class CacheEntry<T>(
    val key: String,
    val value: T,
    val sizeBytes: Long,
    val loadedAtMs: Long = System.currentTimeMillis(),
    var lastAccessMs: Long = System.currentTimeMillis(),
    var accessCount: Int = 0
) {
    fun touch() { lastAccessMs = System.currentTimeMillis(); accessCount++ }
}

/** Unified interface for all model caches */
interface ModelCache<T> {
    suspend fun get(key: String): T?
    suspend fun put(key: String, value: T, sizeBytes: Long)
    fun evict(key: String)
    fun evictAll()
    fun containsKey(key: String): Boolean
    fun currentSizeBytes(): Long
    fun maxSizeBytes(): Long
    fun entries(): List<CacheEntry<T>>
}
