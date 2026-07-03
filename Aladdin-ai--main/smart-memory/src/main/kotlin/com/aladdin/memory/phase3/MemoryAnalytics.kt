package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.MemoryDao
import com.aladdin.memory.db.dao.SummaryDao
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.engine.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Memory Analytics Engine
 *
 * Provides comprehensive statistics and performance monitoring for the memory system:
 *   - Total memory counts by type
 *   - Memory usage statistics
 *   - Retrieval success rate
 *   - Most/least recalled memories
 *   - Memory growth over time
 *   - Storage size estimation
 *   - Embedding quality metrics
 *   - Search performance
 *   - Duplicate memory detection
 *   - Forgetting curve analysis
 */
@Singleton
class MemoryAnalytics @Inject constructor(
    private val memoryDao: MemoryDao,
    private val summaryDao: SummaryDao,
    private val vectorStore: VectorStore
) {
    companion object {
        private const val TAG = "MemoryAnalytics"
        private const val AVG_BYTES_PER_CHAR = 2
        private const val EMBEDDING_BYTES_PER_DIM = 4
        private const val EMBEDDING_DIM = 384
    }

    // ─── Full Report ──────────────────────────────────────────────────────────

    /**
     * Generate a comprehensive analytics report.
     */
    suspend fun generateReport(): MemoryAnalyticsReport = withContext(Dispatchers.IO) {
        val all = memoryDao.getRecent(limit = Int.MAX_VALUE)
        val now = System.currentTimeMillis()

        val report = MemoryAnalyticsReport(
            timestamp = now,
            totalMemories = all.size,
            memoriesByType = computeByType(all),
            memoriesWithEmbeddings = all.count { it.embedding.isNotEmpty() },
            memoriesWithoutEmbeddings = all.count { it.embedding.isEmpty() },
            embeddingCoveragePercent = if (all.isEmpty()) 100f else
                (all.count { it.embedding.isNotEmpty() }.toFloat() / all.size * 100f),
            averageImportanceScore = all.map { it.importanceScore }.average().toFloat(),
            topImportantMemories = getTopImportant(all, 10),
            mostAccessedMemories = getMostAccessed(all, 10),
            leastAccessedMemories = getLeastAccessed(all, 10),
            neverAccessedCount = all.count { it.accessCount == 0 },
            totalAccessCount = all.sumOf { it.accessCount },
            averageAccessCount = all.map { it.accessCount }.average().toFloat(),
            memoryGrowthByDay = computeGrowthByDay(all, 30),
            estimatedStorageSizeBytes = estimateStorageSize(all),
            vectorStoreSize = vectorStore.size,
            averageEmbeddingMagnitude = computeAvgEmbeddingMagnitude(all),
            embeddingQualityScore = computeEmbeddingQualityScore(all),
            potentialDuplicateCount = estimateDuplicates(all),
            oldestMemoryMs = all.minByOrNull { it.createdAt }?.createdAt,
            newestMemoryMs = all.maxByOrNull { it.createdAt }?.createdAt,
            memoriesExpiringSoon = all.count {
                it.expiresAt != null && it.expiresAt > now && it.expiresAt < now + TimeUnit.DAYS.toMillis(7)
            },
            memoriesExpired = all.count { it.expiresAt != null && it.expiresAt!! < now },
            summarizedMemories = all.count { it.isSummarized },
            unsummarizedMemories = all.count { !it.isSummarized },
            importanceDistribution = computeImportanceDistribution(all)
        )

        Log.i(TAG, "Analytics report: ${report.totalMemories} memories, " +
            "${report.embeddingCoveragePercent.toInt()}% embeddings, " +
            "${formatBytes(report.estimatedStorageSizeBytes)} storage")
        report
    }

    // ─── Quick Stats ──────────────────────────────────────────────────────────

    /**
     * Lightweight stats for dashboard display.
     */
    suspend fun getQuickStats(): QuickMemoryStats = withContext(Dispatchers.IO) {
        val count = memoryDao.getCount()
        val avgImportance = memoryDao.getAverageImportance()
        val recent = memoryDao.getRecent(limit = 5)
        QuickMemoryStats(
            totalMemories = count,
            vectorStoreSize = vectorStore.size,
            averageImportance = avgImportance,
            recentMemories = recent.map { it.content.take(80) }
        )
    }

    // ─── Search Performance ───────────────────────────────────────────────────

    /**
     * Benchmark search performance by running multiple test queries.
     */
    suspend fun benchmarkSearch(testQueries: List<String> = defaultTestQueries()): SearchBenchmark =
        withContext(Dispatchers.IO) {
            val results = testQueries.map { query ->
                val start = System.currentTimeMillis()
                val found = memoryDao.searchByKeyword(query, 10)
                val elapsed = System.currentTimeMillis() - start
                QueryBenchmarkResult(query = query, resultCount = found.size, latencyMs = elapsed)
            }
            SearchBenchmark(
                queriesTested = results.size,
                avgLatencyMs = results.map { it.latencyMs }.average().toLong(),
                maxLatencyMs = results.maxByOrNull { it.latencyMs }?.latencyMs ?: 0L,
                avgResultCount = results.map { it.resultCount }.average().toFloat(),
                zeroResultQueries = results.count { it.resultCount == 0 },
                results = results
            )
        }

    // ─── Duplicate Report ─────────────────────────────────────────────────────

    /**
     * Produce a report of potential duplicate memories.
     */
    suspend fun duplicateReport(): List<DuplicateCandidate> = withContext(Dispatchers.IO) {
        val all = memoryDao.getRecent(limit = 2000)
            .filter { it.embedding.isNotEmpty() && it.memoryType == MemoryType.CONVERSATION }

        val candidates = mutableListOf<DuplicateCandidate>()
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                val sim = cosineSimilarity(
                    all[i].embedding.toFloatArray(),
                    all[j].embedding.toFloatArray()
                )
                if (sim > 0.88f) {
                    candidates.add(DuplicateCandidate(
                        memory1Id = all[i].id,
                        memory1Content = all[i].content.take(100),
                        memory2Id = all[j].id,
                        memory2Content = all[j].content.take(100),
                        similarity = sim
                    ))
                }
                if (candidates.size >= 50) break
            }
            if (candidates.size >= 50) break
        }
        candidates.sortedByDescending { it.similarity }
    }

    // ─── Formatted Report String ──────────────────────────────────────────────

    suspend fun buildReportString(): String = withContext(Dispatchers.IO) {
        val r = generateReport()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        buildString {
            appendLine("╔══════════════════════════════════════╗")
            appendLine("║       JARVIS MEMORY ANALYTICS        ║")
            appendLine("╚══════════════════════════════════════╝")
            appendLine("Generated: ${sdf.format(Date(r.timestamp))}")
            appendLine()
            appendLine("── OVERVIEW ──────────────────────────")
            appendLine("Total memories:       ${r.totalMemories}")
            appendLine("Vector store size:    ${r.vectorStoreSize}")
            appendLine("Embedding coverage:   ${r.embeddingCoveragePercent.toInt()}%")
            appendLine("Embedding quality:    ${(r.embeddingQualityScore * 100).toInt()}%")
            appendLine("Estimated storage:    ${formatBytes(r.estimatedStorageSizeBytes)}")
            appendLine()
            appendLine("── BY TYPE ───────────────────────────")
            r.memoriesByType.forEach { (type, count) ->
                appendLine("  $type: $count")
            }
            appendLine()
            appendLine("── IMPORTANCE ────────────────────────")
            appendLine("Avg importance:       ${"%.2f".format(r.averageImportanceScore)}")
            r.importanceDistribution.forEach { (range, count) ->
                appendLine("  $range: $count memories")
            }
            appendLine()
            appendLine("── ACCESS PATTERNS ───────────────────")
            appendLine("Total accesses:       ${r.totalAccessCount}")
            appendLine("Avg access count:     ${"%.1f".format(r.averageAccessCount)}")
            appendLine("Never accessed:       ${r.neverAccessedCount}")
            appendLine()
            appendLine("── TOP MEMORIES (most accessed) ──────")
            r.mostAccessedMemories.take(5).forEachIndexed { i, m ->
                appendLine("${i + 1}. [${m.accessCount}x] ${m.content.take(60)}…")
            }
            appendLine()
            appendLine("── MEMORY GROWTH (last 7 days) ───────")
            // NOTE: memoryGrowthByDay is a Map<String, Int>; Map has no takeLast(),
            // so convert to a List<Pair<K, V>> first (this also makes the (date, count)
            // destructuring unambiguous).
            r.memoryGrowthByDay.toList().takeLast(7).forEach { (date, count) ->
                appendLine("  $date: $count new memories")
            }
            appendLine()
            appendLine("── HOUSEKEEPING ──────────────────────")
            appendLine("Potential duplicates: ${r.potentialDuplicateCount}")
            appendLine("Summarized:           ${r.summarizedMemories}")
            appendLine("Expiring soon:        ${r.memoriesExpiringSoon}")
            appendLine("Expired:              ${r.memoriesExpired}")
            if (r.oldestMemoryMs != null) {
                appendLine("Oldest memory:        ${sdf.format(Date(r.oldestMemoryMs))}")
            }
        }.trim()
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────

    private fun computeByType(all: List<MemoryEntity>): Map<String, Int> =
        all.groupBy { it.memoryType }.mapValues { it.value.size }

    private fun getTopImportant(all: List<MemoryEntity>, n: Int) =
        all.sortedByDescending { it.importanceScore }.take(n)

    private fun getMostAccessed(all: List<MemoryEntity>, n: Int) =
        all.sortedByDescending { it.accessCount }.take(n)

    private fun getLeastAccessed(all: List<MemoryEntity>, n: Int) =
        all.filter { it.accessCount > 0 }.sortedBy { it.accessCount }.take(n)

    private fun computeGrowthByDay(all: List<MemoryEntity>, days: Int): Map<String, Int> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return all.groupBy { sdf.format(Date(it.createdAt)) }
            .mapValues { it.value.size }
            .entries.sortedBy { it.key }.takeLast(days).associate { it.key to it.value }
    }

    private fun estimateStorageSize(all: List<MemoryEntity>): Long {
        var bytes = 0L
        for (m in all) {
            bytes += m.content.length * AVG_BYTES_PER_CHAR
            bytes += m.embedding.size * EMBEDDING_BYTES_PER_DIM
            bytes += 64L  // row overhead
        }
        return bytes
    }

    private fun computeAvgEmbeddingMagnitude(all: List<MemoryEntity>): Float {
        val withEmbeddings = all.filter { it.embedding.isNotEmpty() }
        if (withEmbeddings.isEmpty()) return 0f
        return withEmbeddings.map { m ->
            sqrt(m.embedding.sumOf { v -> v.toDouble() * v }.toFloat())
        }.average().toFloat()
    }

    private fun computeEmbeddingQualityScore(all: List<MemoryEntity>): Float {
        val withEmbeddings = all.filter { it.embedding.size == EMBEDDING_DIM }
        if (withEmbeddings.isEmpty()) return 0f

        // Quality: fraction that have full 384-dim MiniLM embeddings (not BoW fallback)
        val fullDimCount = all.count { it.embedding.size == EMBEDDING_DIM }
        return fullDimCount.toFloat() / all.size.coerceAtLeast(1)
    }

    private fun estimateDuplicates(all: List<MemoryEntity>): Int {
        // Fast heuristic: count memories with identical content prefix (first 50 chars)
        val prefixes = all.groupBy { it.content.take(50).lowercase() }
        return prefixes.values.count { it.size > 1 }
    }

    private fun computeImportanceDistribution(all: List<MemoryEntity>): Map<String, Int> {
        return mapOf(
            "0.0–0.2 (low)" to all.count { it.importanceScore < 0.2f },
            "0.2–0.5 (medium)" to all.count { it.importanceScore in 0.2f..0.5f },
            "0.5–0.8 (high)" to all.count { it.importanceScore in 0.5f..0.8f },
            "0.8–1.0 (critical)" to all.count { it.importanceScore > 0.8f }
        )
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = Math.sqrt(na) * Math.sqrt(nb)
        return if (denom < 1e-8) 0f else (dot / denom).toFloat()
    }

    private fun List<Float>.toFloatArray() = FloatArray(size) { get(it) }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    private fun defaultTestQueries() = listOf(
        "what did I say yesterday",
        "my favorite food",
        "office meeting",
        "family",
        "project deadline"
    )
}

data class MemoryAnalyticsReport(
    val timestamp: Long,
    val totalMemories: Int,
    val memoriesByType: Map<String, Int>,
    val memoriesWithEmbeddings: Int,
    val memoriesWithoutEmbeddings: Int,
    val embeddingCoveragePercent: Float,
    val averageImportanceScore: Float,
    val topImportantMemories: List<MemoryEntity>,
    val mostAccessedMemories: List<MemoryEntity>,
    val leastAccessedMemories: List<MemoryEntity>,
    val neverAccessedCount: Int,
    val totalAccessCount: Int,
    val averageAccessCount: Float,
    val memoryGrowthByDay: Map<String, Int>,
    val estimatedStorageSizeBytes: Long,
    val vectorStoreSize: Int,
    val averageEmbeddingMagnitude: Float,
    val embeddingQualityScore: Float,
    val potentialDuplicateCount: Int,
    val oldestMemoryMs: Long?,
    val newestMemoryMs: Long?,
    val memoriesExpiringSoon: Int,
    val memoriesExpired: Int,
    val summarizedMemories: Int,
    val unsummarizedMemories: Int,
    val importanceDistribution: Map<String, Int>
)

data class QuickMemoryStats(
    val totalMemories: Int,
    val vectorStoreSize: Int,
    val averageImportance: Float,
    val recentMemories: List<String>
)

data class SearchBenchmark(
    val queriesTested: Int,
    val avgLatencyMs: Long,
    val maxLatencyMs: Long,
    val avgResultCount: Float,
    val zeroResultQueries: Int,
    val results: List<QueryBenchmarkResult>
)

data class QueryBenchmarkResult(
    val query: String,
    val resultCount: Int,
    val latencyMs: Long
)

data class DuplicateCandidate(
    val memory1Id: Long,
    val memory1Content: String,
    val memory2Id: Long,
    val memory2Content: String,
    val similarity: Float
)
