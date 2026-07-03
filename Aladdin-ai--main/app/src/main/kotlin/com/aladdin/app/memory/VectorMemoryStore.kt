package com.aladdin.app.memory

import android.content.Context
import android.util.Log
import com.aladdin.app.embedding.EmbeddingModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * VectorMemoryStore — Items 38-42.
 * Item 38: MiniLM embeddings with LRU cache (200 entries).
 * Item 39: Normalized embeddings; improved semantic quality.
 * Item 40: Top-K search with combined score (similarity × importance × recency).
 * Item 41: Memory pruning via exponential decay + importance scoring.
 * Item 42: Context optimization with token-aware trimming.
 */
@Singleton
class VectorMemoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingModel: EmbeddingModel
) {
    companion object {
        private const val TAG = "VectorMemoryStore"
        private const val MAX_ENTRIES = 500
        private const val DECAY_HALF_LIFE_HOURS = 24.0
        private const val PRUNE_THRESHOLD = 0.15f
        private const val CACHE_SIZE = 200
        private const val MAX_CTX_TOKENS = 2048
    }

    data class MemoryEntry(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String, val embedding: FloatArray,
        val timestamp: Long = System.currentTimeMillis(),
        val importance: Float = 0.5f, val tags: List<String> = emptyList(),
        val type: MemoryType = MemoryType.EPISODIC,
        var accessCount: Int = 0, var lastAccessedMs: Long = System.currentTimeMillis()
    )

    enum class MemoryType { SHORT_TERM, LONG_TERM, EPISODIC, SEMANTIC, PROCEDURAL }
    data class SearchResult(val entry: MemoryEntry, val score: Float, val rank: Int)

    private val entries = mutableListOf<MemoryEntry>()
    private val lock = ReentrantReadWriteLock()
    // Item 38: LRU embedding cache
    private val cache = object : LinkedHashMap<String, FloatArray>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, FloatArray>?) = size > CACHE_SIZE
    }

    suspend fun add(content: String, importance: Float = 0.5f, tags: List<String> = emptyList(), type: MemoryType = MemoryType.EPISODIC): MemoryEntry = withContext(Dispatchers.IO) {
        val emb = getEmbedding(content)
        val entry = MemoryEntry(content = content, embedding = emb, importance = importance.coerceIn(0f, 1f), tags = tags, type = type)
        lock.writeLock().lock(); try { entries.add(entry) } finally { lock.writeLock().unlock() }
        if (entries.size > MAX_ENTRIES) pruneMemory()
        entry
    }

    /** Item 40: Top-K semantic search with combined scoring. */
    suspend fun search(query: String, topK: Int = 5, typeFilter: MemoryType? = null, minScore: Float = 0.2f): List<SearchResult> = withContext(Dispatchers.IO) {
        val qEmb = getEmbedding(query); val now = System.currentTimeMillis()
        lock.readLock().lock(); val snap = try { entries.toList() } finally { lock.readLock().unlock() }
        val filtered = if (typeFilter != null) snap.filter { it.type == typeFilter } else snap
        val scored = filtered.mapNotNull { e ->
            val sim = cos(qEmb, e.embedding); if (sim < minScore) return@mapNotNull null
            val age = (now - e.timestamp) / 3_600_000.0
            val rec = exp(-age / DECAY_HALF_LIFE_HOURS).toFloat()
            val combined = sim * 0.6f + e.importance * 0.2f + rec * 0.2f
            e.accessCount++; e.lastAccessedMs = now; Pair(e, combined)
        }.sortedByDescending { it.second }.take(topK).mapIndexed { rank, (e, s) -> SearchResult(e, s, rank + 1) }
        Log.d(TAG, "Search '${'{'}query.take(40){'}'}' → \${scored.size} results")
        scored
    }

    /** Item 41: Prune low-retention memories via decay scoring. */
    suspend fun pruneMemory() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis(); val before = entries.size
        lock.writeLock().lock()
        try {
            entries.removeIf { e ->
                if (e.type == MemoryType.PROCEDURAL || e.type == MemoryType.SEMANTIC) return@removeIf false
                val age = (now - e.timestamp) / 3_600_000.0
                val dec = exp(-age / DECAY_HALF_LIFE_HOURS).toFloat()
                val ret = e.importance * 0.5f + dec * 0.3f + (e.accessCount.coerceAtMost(10) / 10f) * 0.2f
                ret < PRUNE_THRESHOLD
            }
        } finally { lock.writeLock().unlock() }
        val pruned = before - entries.size; if (pruned > 0) Log.i(TAG, "Pruned \$pruned (\${entries.size} remain)")
    }

    /** Item 42: Build token-limited context string from memory. */
    suspend fun buildContext(query: String, maxEntries: Int = 8): String {
        val results = search(query, topK = maxEntries); if (results.isEmpty()) return ""
        val sb = StringBuilder("Relevant memory:\n"); var tokens = 20
        for (r in results) {
            val line = "[\${r.entry.type.name}] \${r.entry.content}\n"
            val t = (line.length / 4).coerceAtLeast(1); if (tokens + t > MAX_CTX_TOKENS) break
            sb.append(line); tokens += t
        }
        return sb.toString().trim()
    }

    fun summarizeConversation(messages: List<String>, maxTokens: Int = 500): String {
        if (messages.isEmpty()) return ""
        val ret = mutableListOf<String>(); var t = 0
        for (m in messages.reversed()) { val mt = (m.length / 4).coerceAtLeast(1); if (t + mt > maxTokens) break; ret.add(0, m); t += mt }
        return if (messages.size > ret.size) "[\${messages.size - ret.size} earlier]\n\${ret.joinToString("\n")}" else ret.joinToString("\n")
    }

    fun getStats() = mapOf("total" to entries.size, "by_type" to entries.groupBy { it.type.name }.mapValues { it.value.size }, "cache" to cache.size)
    fun clear() { lock.writeLock().lock(); try { entries.clear(); cache.clear() } finally { lock.writeLock().unlock() } }

    private suspend fun getEmbedding(text: String): FloatArray {
        synchronized(cache) { cache[text]?.let { return it } }
        val emb = withContext(Dispatchers.Default) { embeddingModel.embed(text) }
        synchronized(cache) { cache[text] = emb }; return emb
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }
}