package com.aladdin.memory.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistent Embedding Cache
 *
 * Saves computed embedding vectors to disk so they survive app restarts.
 * Strategy:
 *   - On first embed: compute via EmbeddingEngine, save to cache
 *   - On subsequent calls with same content hash: load from cache (fast)
 *   - Cache invalidated only when content changes
 *   - Uses JSON file per 1000 entries (sharded for performance)
 *
 * This eliminates the need to regenerate all embeddings on every app start.
 * The VectorStore loads from Room DB (which stores embeddings), so this cache
 * is mainly for the case where a new embedding is needed before DB write.
 */
class EmbeddingPersistenceCache(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingPersistenceCache"
        private const val CACHE_DIR = "embedding_cache"
        private const val SHARD_SIZE = 500
        private const val MAX_CACHE_ENTRIES = 10_000
    }

    private val gson = Gson()
    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
    }

    // In-memory LRU-style map (shard_id → map<hash, floatArray>)
    private val shardCache = LinkedHashMap<Int, MutableMap<String, FloatArray>>(16, 0.75f, true)

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Get a cached embedding for [text] if it exists, or null.
     */
    suspend fun get(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val hash = contentHash(text)
        val shardId = shardIdForHash(hash)
        val shard = loadShard(shardId)
        shard[hash]
    }

    /**
     * Cache an embedding for [text].
     */
    suspend fun put(text: String, embedding: FloatArray) = withContext(Dispatchers.IO) {
        val hash = contentHash(text)
        val shardId = shardIdForHash(hash)
        val shard = loadShard(shardId)
        shard[hash] = embedding
        saveShard(shardId, shard)
        Log.d(TAG, "Cached embedding for hash=$hash shard=$shardId")
    }

    /**
     * Get or compute embedding using cache.
     * Only computes if not already cached.
     */
    suspend fun getOrCompute(text: String, compute: suspend (String) -> FloatArray): FloatArray {
        return get(text) ?: run {
            val embedding = compute(text)
            put(text, embedding)
            embedding
        }
    }

    /**
     * Remove cached embedding for [text].
     */
    suspend fun remove(text: String) = withContext(Dispatchers.IO) {
        val hash = contentHash(text)
        val shardId = shardIdForHash(hash)
        val shard = loadShard(shardId)
        if (shard.remove(hash) != null) {
            saveShard(shardId, shard)
        }
    }

    /**
     * Clear all cached embeddings.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        shardCache.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Embedding cache cleared")
    }

    /**
     * Get cache statistics.
     */
    suspend fun getStats(): EmbeddingCacheStats = withContext(Dispatchers.IO) {
        val files = cacheDir.listFiles() ?: emptyArray()
        var totalEntries = 0
        var totalSizeBytes = 0L
        for (file in files) {
            totalSizeBytes += file.length()
            try {
                val shard = loadShardFromFile(file)
                totalEntries += shard.size
            } catch (_: Exception) {}
        }
        EmbeddingCacheStats(
            totalEntries = totalEntries,
            shardCount = files.size,
            totalSizeBytes = totalSizeBytes
        )
    }

    // ─── Shard Management ─────────────────────────────────────────────────────

    private fun shardIdForHash(hash: String): Int {
        val code = hash.hashCode() and 0x7FFFFFFF
        return code % (MAX_CACHE_ENTRIES / SHARD_SIZE).coerceAtLeast(1)
    }

    private fun shardFile(shardId: Int): File = File(cacheDir, "shard_$shardId.json")

    private fun loadShard(shardId: Int): MutableMap<String, FloatArray> {
        shardCache[shardId]?.let { return it }
        val file = shardFile(shardId)
        val shard = if (file.exists()) {
            try { loadShardFromFile(file) } catch (_: Exception) { mutableMapOf() }
        } else {
            mutableMapOf()
        }
        shardCache[shardId] = shard
        // Evict oldest shards if too many loaded
        if (shardCache.size > 20) {
            val oldest = shardCache.keys.first()
            shardCache.remove(oldest)
        }
        return shard
    }

    private fun loadShardFromFile(file: File): MutableMap<String, FloatArray> {
        val type = object : TypeToken<Map<String, List<Float>>>() {}.type
        val raw = gson.fromJson<Map<String, List<Float>>>(file.readText(), type) ?: return mutableMapOf()
        return raw.mapValues { (_, v) -> FloatArray(v.size) { v[it] } }.toMutableMap()
    }

    private fun saveShard(shardId: Int, shard: MutableMap<String, FloatArray>) {
        val file = shardFile(shardId)
        val serializable = shard.mapValues { (_, v) -> v.toList() }
        file.writeText(gson.toJson(serializable))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun contentHash(text: String): String {
        val trimmed = text.trim().take(512)
        val h = trimmed.hashCode().toLong() and 0xFFFFFFFFL
        return h.toString(16).padStart(8, '0')
    }
}

data class EmbeddingCacheStats(
    val totalEntries: Int,
    val shardCount: Int,
    val totalSizeBytes: Long
)
