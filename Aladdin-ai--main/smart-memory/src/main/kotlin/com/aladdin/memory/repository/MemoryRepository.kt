package com.aladdin.memory.repository

import android.util.Log
import com.aladdin.memory.db.dao.MemoryDao
import com.aladdin.memory.db.dao.SummaryDao
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.db.entity.SummaryEntity
import com.aladdin.memory.engine.BM25Engine
import com.aladdin.memory.engine.EmbeddingEngine
import com.aladdin.memory.engine.HybridSearchEngine
import com.aladdin.memory.engine.ImportanceScorer
import com.aladdin.memory.engine.MemorySummarizer
import com.aladdin.memory.model.Memory
import com.aladdin.memory.model.MemorySearchResult
import com.aladdin.memory.model.MemoryStats
import com.aladdin.memory.model.NewMemory
import com.aladdin.memory.model.SearchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primary repository for the Smart Memory System.
 *
 * Responsibilities:
 *  - CRUD for [MemoryEntity] via [MemoryDao]
 *  - Embedding generation on write
 *  - BM25 + vector index maintenance
 *  - Hybrid search (BM25 + semantic)
 *  - Importance score updates
 *  - Context compression for LLM injection
 *  - Nightly summarization coordination
 *  - LRU-based forgetting (7 days)
 *
 * All methods are coroutine-safe and run on [Dispatchers.IO].
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val summaryDao: SummaryDao,
    private val embeddingEngine: EmbeddingEngine,
    private val hybridSearch: HybridSearchEngine,
    private val importanceScorer: ImportanceScorer,
    private val summarizer: MemorySummarizer,
    private val bm25Engine: BM25Engine
) {
    companion object {
        private const val TAG = "MemoryRepository"
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    /**
     * Call once at app startup: loads all memories into the in-memory indexes.
     * Takes ~100ms for 1k memories, ~500ms for 10k.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val all = memoryDao.getCandidatesForVectorSearch(limit = 10_000)
        hybridSearch.indexAll(all)
        Log.i(TAG, "Warm-up complete: ${all.size} memories indexed")
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    /**
     * Save a new memory. Generates embedding, computes initial importance, and
     * updates BM25 + vector indexes synchronously before returning.
     */
    suspend fun addMemory(new: NewMemory): Long = withContext(Dispatchers.IO) {
        val importanceScore = importanceScorer.initialScore(new.content, new.memoryType)
        val tfMap = bm25Engine.computeTfMap(new.content)

        // Compute embedding
        val embedding = embeddingEngine.embed(new.content)

        val expiresAt = new.expiresInDays?.let {
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it.toLong())
        }

        val entity = MemoryEntity(
            content = new.content,
            memoryType = new.memoryType,
            tags = new.tags,
            embedding = embedding.toList(),
            importanceScore = importanceScore,
            sessionId = new.sessionId,
            contactId = new.contactId,
            source = new.source,
            expiresAt = expiresAt,
            tfMap = tfMap
        )

        val id = memoryDao.insert(entity)

        // Update indexes with the assigned ID
        hybridSearch.index(entity.copy(id = id))

        Log.d(TAG, "Memory added: id=$id type=${new.memoryType} importance=$importanceScore")
        id
    }

    /**
     * Batch add memories — more efficient than repeated [addMemory] calls.
     */
    suspend fun addMemories(items: List<NewMemory>): List<Long> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = items.map { new ->
            val embedding = embeddingEngine.embed(new.content)
            val tfMap = bm25Engine.computeTfMap(new.content)
            MemoryEntity(
                content = new.content,
                memoryType = new.memoryType,
                tags = new.tags,
                embedding = embedding.toList(),
                importanceScore = importanceScorer.initialScore(new.content, new.memoryType),
                sessionId = new.sessionId,
                contactId = new.contactId,
                source = new.source,
                tfMap = tfMap,
                createdAt = now
            )
        }
        val ids = memoryDao.insertAll(entities)
        val indexed = entities.zip(ids) { e, id -> e.copy(id = id) }
        hybridSearch.indexAll(indexed)
        ids
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    fun observeAll(): Flow<List<Memory>> =
        memoryDao.observeAll().map { list -> list.map { Memory.from(it) } }

    fun observeByType(type: String): Flow<List<Memory>> =
        memoryDao.observeByType(type).map { list -> list.map { Memory.from(it) } }

    fun observeTopImportant(limit: Int = 20): Flow<List<Memory>> =
        memoryDao.observeTopImportant(limit).map { list -> list.map { Memory.from(it) } }

    fun observeCount(): Flow<Int> = memoryDao.observeCount()

    suspend fun getById(id: Long): Memory? = withContext(Dispatchers.IO) {
        memoryDao.getById(id)?.let { Memory.from(it) }
    }

    suspend fun getRecentRaw(limit: Int = 50): List<MemoryEntity> =
        withContext(Dispatchers.IO) { memoryDao.getRecent(limit) }

    suspend fun getBySession(sessionId: String): List<MemoryEntity> =
        withContext(Dispatchers.IO) { memoryDao.getBySession(sessionId) }

    // ─── Update ───────────────────────────────────────────────────────────────

    suspend fun updateContent(id: Long, newContent: String) = withContext(Dispatchers.IO) {
        val existing = memoryDao.getById(id) ?: return@withContext
        val embedding = embeddingEngine.embed(newContent)
        val tfMap = bm25Engine.computeTfMap(newContent)
        val updated = existing.copy(
            content = newContent,
            embedding = embedding.toList(),
            tfMap = tfMap
        )
        memoryDao.update(updated)
        hybridSearch.index(updated)
    }

    suspend fun updateImportance(id: Long) = withContext(Dispatchers.IO) {
        val memory = memoryDao.getById(id) ?: return@withContext
        val newScore = importanceScorer.score(memory)
        memoryDao.updateImportanceAndAccess(id, newScore)
    }

    /** Record access, bump count, and recalculate importance. */
    suspend fun recordAccess(ids: List<Long>) = withContext(Dispatchers.IO) {
        memoryDao.bumpAccessCount(ids)
        ids.forEach { updateImportance(it) }
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        memoryDao.deleteById(id)
        hybridSearch.removeFromIndex(id)
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Hybrid BM25 + semantic search.
     * Records access for returned memories automatically.
     */
    suspend fun search(
        query: String,
        k: Int = 10,
        filter: SearchFilter = SearchFilter()
    ): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        val candidates = memoryDao.getCandidatesForVectorSearch(limit = 5_000)
        val results = hybridSearch.search(query, k, filter = filter, allMemories = candidates)

        // Record access for top results
        if (results.isNotEmpty()) {
            recordAccess(results.map { it.memory.id })
        }

        results
    }

    /** Pure semantic search. */
    suspend fun semanticSearch(query: String, k: Int = 10): List<MemorySearchResult> =
        withContext(Dispatchers.IO) {
            val semResults = hybridSearch.semanticSearch(query, k)
            val ids = semResults.map { it.id }
            val memMap = ids.mapNotNull { memoryDao.getById(it) }.associateBy { it.id }
            if (ids.isNotEmpty()) recordAccess(ids)
            semResults.mapNotNull { r ->
                memMap[r.id]?.let { MemorySearchResult(it, r.score, semanticScore = r.score) }
            }
        }

    /** Get top-N important memories for LLM context injection. */
    suspend fun getContextMemories(
        query: String,
        maxTokens: Int = 2048,
        filter: SearchFilter = SearchFilter()
    ): String = withContext(Dispatchers.IO) {
        val results = search(query, k = 20, filter = filter)
        val memories = results.map { it.memory }
        val compressed = summarizer.compressForContext(memories, maxTokens)
        summarizer.buildContextString(compressed)
    }

    // ─── Summarization ────────────────────────────────────────────────────────

    /**
     * Summarize all un-summarized conversation memories older than [beforeMs].
     * Called by [MemorySummaryWorker] nightly.
     */
    suspend fun summarizeOldMemories(
        beforeMs: Long = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
    ): Int = withContext(Dispatchers.IO) {
        val toSummarize = memoryDao.getUnsummarized(beforeMs, limit = 200)
        if (toSummarize.isEmpty()) return@withContext 0

        // Group by session, summarize each group
        val grouped = toSummarize.groupBy { it.sessionId ?: "default" }
        var count = 0

        for ((sessionId, memories) in grouped) {
            if (memories.size < 3) continue
            val summaryText = summarizer.summarize(memories)
            if (summaryText.isBlank()) continue

            // Store summary as a new SUMMARY memory
            val summaryEmbedding = embeddingEngine.embed(summaryText)
            val summaryEntity = MemoryEntity(
                content = summaryText,
                memoryType = MemoryType.SUMMARY,
                embedding = summaryEmbedding.toList(),
                importanceScore = 0.75f,
                sessionId = sessionId,
                source = "auto-summary"
            )
            val summaryId = memoryDao.insert(summaryEntity)
            hybridSearch.index(summaryEntity.copy(id = summaryId))

            // Mark originals as summarized
            memoryDao.markSummarized(memories.map { it.id })
            count += memories.size
        }

        // Also persist a daily summary entry
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        summaryDao.insert(
            SummaryEntity(
                dateKey = dateKey,
                content = "Summarized $count memories",
                memoryCount = count
            )
        )

        Log.i(TAG, "Summarized $count memories into SUMMARY entries")
        count
    }

    // ─── Forgetting (LRU 7-day) ───────────────────────────────────────────────

    /**
     * Remove expired memories and low-importance memories older than 7 days.
     * Called by [MemoryCleanupWorker] nightly.
     */
    suspend fun forget(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val staleMs = now - TimeUnit.DAYS.toMillis(7)

        val expired = memoryDao.deleteExpired(now)
        val stale = memoryDao.deleteLowImportanceOlderThan(staleMs, maxImportance = 0.25f)

        val total = expired + stale
        Log.i(TAG, "Forgot $total memories ($expired expired + $stale stale)")
        total
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    suspend fun getStats(): MemoryStats = withContext(Dispatchers.IO) {
        val all = memoryDao.getRecent(limit = Int.MAX_VALUE)
        MemoryStats(
            totalMemories = all.size,
            averageImportance = all.map { it.importanceScore }.average().toFloat(),
            vectorStoreSize = hybridSearch.let { 0 }, // exposed via engine if needed
            bm25IndexSize = bm25Engine.size,
            oldestMemoryMs = all.minByOrNull { it.createdAt }?.createdAt,
            newestMemoryMs = all.maxByOrNull { it.createdAt }?.createdAt
        )
    }
}
