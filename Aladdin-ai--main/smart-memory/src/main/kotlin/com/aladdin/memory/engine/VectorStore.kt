package com.aladdin.memory.engine

import android.util.Log
import kotlin.math.sqrt

/**
 * In-memory FAISS-like vector store with cosine similarity retrieval.
 *
 * Stores embedding vectors keyed by memory ID. Supports:
 *   - upsert / delete
 *   - top-K cosine similarity search
 *   - filtered search by candidate ID whitelist
 *   - approximate search via random-projection LSH for large corpora
 *
 * At app start, rebuild the store from DB via [VectorStoreBuilder].
 * Write operations are O(1); reads are O(n·d) brute-force up to 10k entries,
 * then fall back to LSH approximate mode.
 */
class VectorStore(private val dim: Int) {

    companion object {
        private const val TAG = "VectorStore"
        private const val LSH_THRESHOLD = 5_000   // switch to LSH above this
        private const val LSH_PLANES = 32         // random hyperplane projections
    }

    // id → normalized float vector
    private val vectors = HashMap<Long, FloatArray>(1024)

    // LSH index: plane index → list of ids with positive projection
    private val lshBuckets = HashMap<Int, MutableSet<Long>>()
    private var lshPlanes: Array<FloatArray>? = null

    // ─── Mutations ────────────────────────────────────────────────────────────

    /** Add or update a vector. [vec] must be normalized. */
    @Synchronized
    fun upsert(id: Long, vec: FloatArray) {
        require(vec.size == dim) { "Expected dim=$dim, got ${vec.size}" }
        vectors[id] = vec
        if (vectors.size >= LSH_THRESHOLD) {
            updateLSH(id, vec)
        }
    }

    /** Batch upsert — more efficient at startup. */
    @Synchronized
    fun upsertAll(entries: Map<Long, FloatArray>) {
        entries.forEach { (id, vec) ->
            if (vec.size == dim) vectors[id] = vec
        }
        if (vectors.size >= LSH_THRESHOLD) rebuildLSH()
        Log.d(TAG, "VectorStore loaded ${vectors.size} vectors")
    }

    @Synchronized
    fun remove(id: Long) {
        vectors.remove(id)
        lshBuckets.values.forEach { it.remove(id) }
    }

    @Synchronized
    fun clear() {
        vectors.clear()
        lshBuckets.clear()
        lshPlanes = null
    }

    val size: Int get() = vectors.size

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Returns top-[k] memory IDs with their cosine similarity scores,
     * sorted descending by similarity.
     */
    @Synchronized
    fun search(query: FloatArray, k: Int = 10, minScore: Float = 0.0f): List<SearchResult> {
        if (vectors.isEmpty()) return emptyList()
        require(query.size == dim) { "Query dim mismatch: expected $dim got ${query.size}" }

        val candidates = if (vectors.size >= LSH_THRESHOLD && lshPlanes != null) {
            lshCandidates(query)
        } else {
            vectors.keys
        }

        return candidates
            .mapNotNull { id ->
                val v = vectors[id] ?: return@mapNotNull null
                val score = cosineSimilarity(query, v)
                if (score >= minScore) SearchResult(id, score) else null
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /**
     * Search within a specific set of candidate IDs (used for filtered hybrid search).
     */
    @Synchronized
    fun searchAmong(query: FloatArray, candidateIds: Collection<Long>, k: Int = 10): List<SearchResult> {
        if (candidateIds.isEmpty() || vectors.isEmpty()) return emptyList()
        return candidateIds
            .mapNotNull { id ->
                val v = vectors[id] ?: return@mapNotNull null
                val score = cosineSimilarity(query, v)
                SearchResult(id, score)
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    // ─── Cosine Similarity ────────────────────────────────────────────────────

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-8) 0f else (dot / denom).toFloat()
    }

    // ─── LSH (Locality-Sensitive Hashing) ─────────────────────────────────────

    private fun rebuildLSH() {
        val rng = java.util.Random(42L)
        lshPlanes = Array(LSH_PLANES) {
            FloatArray(dim) { rng.nextGaussian().toFloat() }.also { normalize(it) }
        }
        lshBuckets.clear()
        vectors.forEach { (id, vec) -> updateLSH(id, vec) }
        Log.d(TAG, "LSH index rebuilt with ${LSH_PLANES} planes")
    }

    private fun updateLSH(id: Long, vec: FloatArray) {
        val planes = lshPlanes ?: return
        for (p in planes.indices) {
            if (dot(vec, planes[p]) >= 0) {
                lshBuckets.getOrPut(p) { mutableSetOf() }.add(id)
            }
        }
    }

    private fun lshCandidates(query: FloatArray): Collection<Long> {
        val planes = lshPlanes ?: return vectors.keys
        val candidates = mutableSetOf<Long>()
        for (p in planes.indices) {
            if (dot(query, planes[p]) >= 0) {
                candidates.addAll(lshBuckets[p] ?: emptySet())
            }
        }
        // If too few candidates, add random set
        if (candidates.size < 100) {
            candidates.addAll(vectors.keys.take(500))
        }
        return candidates
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun normalize(vec: FloatArray) {
        val norm = sqrt(vec.sumOf { it.toDouble() * it }.toFloat()).coerceAtLeast(1e-8f)
        for (i in vec.indices) vec[i] /= norm
    }

    data class SearchResult(val id: Long, val score: Float)
}
