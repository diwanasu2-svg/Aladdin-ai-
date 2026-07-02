package com.aladdin.memory.engine

import android.util.Log
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.model.MemorySearchResult
import com.aladdin.memory.model.SearchFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid BM25 + Semantic search engine.
 *
 * Combines:
 *   1. BM25 keyword score  (lexical match)
 *   2. Cosine similarity   (semantic match from embedding)
 *
 * Final score = α * semanticScore + (1-α) * bm25NormalizedScore
 *
 * Default α = 0.6 (semantic-leaning for conversational queries).
 * Adjust for keyword-heavy queries (e.g., exact fact lookup).
 *
 * Filter support:
 *   - memory_type filter
 *   - date range filter
 *   - minimum importance threshold
 *   - contact ID filter
 */
@Singleton
class HybridSearchEngine @Inject constructor(
    private val embeddingEngine: EmbeddingEngine,
    private val vectorStore: VectorStore,
    private val bm25Engine: BM25Engine
) {
    companion object {
        private const val TAG = "HybridSearch"
        private const val DEFAULT_ALPHA = 0.6f      // weight for semantic score
        private const val CANDIDATE_MULTIPLIER = 5  // fetch 5x final k for re-ranking
    }

    // ─── Index Management ─────────────────────────────────────────────────────

    /** Index or re-index a single memory. */
    suspend fun index(memory: MemoryEntity) {
        bm25Engine.upsert(memory.id, memory.content + " " + (memory.summary ?: ""))
        if (memory.embedding.isNotEmpty()) {
            vectorStore.upsert(memory.id, memory.embedding.toFloatArray())
        }
    }

    /** Bulk index on startup. More efficient than repeated [index] calls. */
    suspend fun indexAll(memories: List<MemoryEntity>) {
        val vectors = HashMap<Long, FloatArray>()
        memories.forEach { m ->
            bm25Engine.upsert(m.id, m.content + " " + (m.summary ?: ""))
            if (m.embedding.isNotEmpty()) {
                vectors[m.id] = m.embedding.toFloatArray()
            }
        }
        vectorStore.upsertAll(vectors)
        Log.i(TAG, "Indexed ${memories.size} memories (BM25=${bm25Engine.size}, vectors=${vectorStore.size})")
    }

    fun removeFromIndex(id: Long) {
        bm25Engine.remove(id)
        vectorStore.remove(id)
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Hybrid search.
     *
     * @param query   Natural-language query string
     * @param k       Number of results to return
     * @param alpha   Semantic score weight [0,1]; 0 = pure BM25, 1 = pure semantic
     * @param filter  Optional search filters
     * @param allMemories  Full memory list for filtering and hydration
     */
    suspend fun search(
        query: String,
        k: Int = 10,
        alpha: Float = DEFAULT_ALPHA,
        filter: SearchFilter = SearchFilter(),
        allMemories: List<MemoryEntity>
    ): List<MemorySearchResult> {
        if (query.isBlank()) return emptyList()
        val fetch = k * CANDIDATE_MULTIPLIER

        // 1. BM25 candidates
        val bm25Results = bm25Engine.search(query, fetch)
        val bm25Scores = bm25Results.associate { it.id to it.score }
        val bm25Max = bm25Results.firstOrNull()?.score?.coerceAtLeast(1e-6f) ?: 1e-6f

        // 2. Semantic candidates
        val queryVec = embeddingEngine.embed(query)
        val semanticResults = vectorStore.search(queryVec, fetch, minScore = 0.1f)
        val semanticScores = semanticResults.associate { it.id to it.score }

        // 3. Merge candidate sets
        val allCandidateIds = (bm25Scores.keys + semanticScores.keys).toSet()

        // 4. Build memory map for filtering
        val memoryMap = allMemories.associateBy { it.id }

        // 5. Apply filters and compute hybrid score
        val results = allCandidateIds.mapNotNull { id ->
            val memory = memoryMap[id] ?: return@mapNotNull null

            // Apply filters
            if (!filter.matches(memory)) return@mapNotNull null

            val semScore = semanticScores[id] ?: 0f
            val bm25Score = (bm25Scores[id] ?: 0f) / bm25Max  // normalize to [0,1]
            val hybridScore = alpha * semScore + (1 - alpha) * bm25Score

            MemorySearchResult(
                memory = memory,
                score = hybridScore,
                semanticScore = semScore,
                bm25Score = bm25Score
            )
        }.sortedByDescending { it.score }.take(k)

        Log.d(TAG, "Hybrid search '$query': ${results.size} results (bm25=${bm25Results.size} sem=${semanticResults.size})")
        return results
    }

    /**
     * Pure semantic search for conceptually related memories.
     */
    suspend fun semanticSearch(query: String, k: Int = 10): List<VectorStore.SearchResult> {
        val vec = embeddingEngine.embed(query)
        return vectorStore.search(vec, k)
    }

    /**
     * Pure BM25 keyword search.
     */
    fun keywordSearch(query: String, k: Int = 10): List<BM25Engine.BM25Result> =
        bm25Engine.search(query, k)
}
