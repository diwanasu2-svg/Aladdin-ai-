package com.aladdin.performance.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache for sentence embedding models.
 * Also caches computed embedding vectors to avoid re-inference on repeated strings.
 * RAM: 30 MB model + 20 MB embedding vector cache  |  Disk: 100 MB
 */
class EmbeddingModelCache(context: Context) {

    companion object {
        private const val TAG = "EmbeddingModelCache"
        const val KEY_MODEL = "embedding-model"
        private const val VECTOR_CACHE_SIZE = 1000  // # embeddings kept in RAM
    }

    private val ram  = LruModelCache<ByteArray>(maxBytes = 30 * 1024 * 1024L)
    private val disk = DiskModelCache(context, "embedding_cache", 100 * 1024 * 1024L)

    // Vector cache: text → FloatArray (LRU, last 1000 queries)
    private val vectorCache = object : LinkedHashMap<String, FloatArray>(VECTOR_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) =
            size > VECTOR_CACHE_SIZE
    }

    var state: CacheState = CacheState.NOT_LOADED
        private set

    suspend fun loadModel(assetPath: String, context: Context): ByteArray? =
        withContext(Dispatchers.IO) {
            ram.get(KEY_MODEL)?.also { Log.d(TAG, "RAM hit: embedding model") } ?:
            disk.get(KEY_MODEL)?.also { b ->
                ram.put(KEY_MODEL, b, b.size.toLong()); state = CacheState.LOADED
            } ?: run {
                state = CacheState.LOADING
                val b = runCatching { context.assets.open(assetPath).readBytes() }.getOrNull()
                b?.let { disk.put(KEY_MODEL, it); ram.put(KEY_MODEL, it, it.size.toLong()); state = CacheState.LOADED }
                    ?: run { state = CacheState.ERROR }
                b
            }
        }

    @Synchronized fun getCachedVector(text: String): FloatArray? = vectorCache[text]

    @Synchronized fun cacheVector(text: String, vector: FloatArray) {
        vectorCache[text] = vector
    }

    @Synchronized fun clearVectorCache() = vectorCache.clear()

    fun evictAll() { ram.evictAll(); disk.evictAll(); clearVectorCache(); state = CacheState.NOT_LOADED }
    fun vectorCacheSize() = vectorCache.size
    fun ramUsageBytes() = ram.currentSizeBytes()
}
