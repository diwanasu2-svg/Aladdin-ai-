package com.aladdin.performance.cache

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe LRU RAM cache for ML models.
 * Evicts least-recently-used entry when capacity is exceeded.
 */
class LruModelCache<T>(
    private val maxBytes: Long = 150 * 1024 * 1024L   // 150 MB default
) : ModelCache<T> {

    companion object { private const val TAG = "LruModelCache" }

    private val map   = LinkedHashMap<String, CacheEntry<T>>(16, 0.75f, true)
    private val lock  = ReentrantReadWriteLock()
    private var usedBytes = 0L

    override suspend fun get(key: String): T? = lock.write {
        map[key]?.also { it.touch() }?.value
    }

    override suspend fun put(key: String, value: T, sizeBytes: Long): Unit = lock.write {
        evict(key)                         // remove old entry if re-inserting
        while (usedBytes + sizeBytes > maxBytes && map.isNotEmpty()) {
            val lru = map.entries.first()
            Log.d(TAG, "LRU evict: ${lru.key} (${lru.value.sizeBytes / 1024}KB)")
            usedBytes -= lru.value.sizeBytes
            map.remove(lru.key)
        }
        map[key] = CacheEntry(key, value, sizeBytes)
        usedBytes += sizeBytes
        Log.i(TAG, "Cached '$key' (${sizeBytes / 1024}KB) — total ${usedBytes / 1024 / 1024}MB / ${maxBytes / 1024 / 1024}MB")
        Unit
    }

    override fun evict(key: String): Unit = lock.write {
        map.remove(key)?.let { usedBytes -= it.sizeBytes }
        Unit
    }

    override fun evictAll() = lock.write { map.clear(); usedBytes = 0 }

    override fun containsKey(key: String) = lock.read { map.containsKey(key) }
    override fun currentSizeBytes() = lock.read { usedBytes }
    override fun maxSizeBytes() = maxBytes
    override fun entries() = lock.read { map.values.toList() }
}
