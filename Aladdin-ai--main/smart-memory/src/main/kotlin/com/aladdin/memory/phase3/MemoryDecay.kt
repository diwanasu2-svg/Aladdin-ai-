package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.MemoryDao
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.engine.HybridSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Memory Decay System
 *
 * Gradually decreases importance of old/irrelevant memories using an
 * Ebbinghaus forgetting curve. Merges duplicates, archives irrelevant ones,
 * and keeps frequently-accessed memories strong.
 *
 * Decay model:
 *   importance(t) = base_importance × e^(-decay_rate × days_since_access)
 *
 * Protected memories (importance > 0.85, PREFERENCE/FACT types) never decay
 * below a minimum floor.
 *
 * Called nightly by MemoryCleanupWorker.
 */
@Singleton
class MemoryDecay @Inject constructor(
    private val memoryDao: MemoryDao,
    private val hybridSearch: HybridSearchEngine
) {
    companion object {
        private const val TAG = "MemoryDecay"

        // How quickly importance drops per day without access (0.05 = 5% per day)
        private const val DECAY_RATE_FAST = 0.08f    // low-importance memories
        private const val DECAY_RATE_NORMAL = 0.03f  // medium importance
        private const val DECAY_RATE_SLOW = 0.01f    // high importance

        // Importance floor — never drop below this
        private const val FLOOR_PROTECTED = 0.20f    // protected types
        private const val FLOOR_DEFAULT = 0.05f      // other types

        // Archive threshold — if below this AND old enough, soft-delete
        private const val ARCHIVE_THRESHOLD = 0.05f
        private const val ARCHIVE_AGE_DAYS = 30L

        // Duplicate similarity threshold
        private const val DUPLICATE_THRESHOLD = 0.93f

        // Boost per access
        private const val ACCESS_BOOST = 0.05f
    }

    // ─── Main Decay Pass ──────────────────────────────────────────────────────

    /**
     * Run the full decay pass over all memories.
     * Returns stats about what changed.
     */
    suspend fun runDecayPass(): DecayResult = withContext(Dispatchers.IO) {
        val allMemories = memoryDao.getCandidatesForVectorSearch(limit = 50_000)
        val now = System.currentTimeMillis()

        var decayed = 0
        var archived = 0
        var strengthened = 0

        for (memory in allMemories) {
            val daysSinceAccess = TimeUnit.MILLISECONDS.toDays(
                now - memory.lastAccessedAt
            ).toFloat().coerceAtLeast(0f)

            val newScore = computeDecayedScore(memory, daysSinceAccess)

            if (newScore != memory.importanceScore) {
                val floor = getFloor(memory)

                if (newScore < memory.importanceScore) {
                    // Memory is decaying
                    decayed++
                } else {
                    strengthened++
                }

                memoryDao.updateImportanceAndAccess(memory.id, max(newScore, floor))
            }

            // Archive check
            val ageDays = TimeUnit.MILLISECONDS.toDays(now - memory.createdAt)
            if (memory.importanceScore < ARCHIVE_THRESHOLD &&
                ageDays > ARCHIVE_AGE_DAYS &&
                !isProtected(memory)) {
                memoryDao.deleteById(memory.id)
                hybridSearch.removeFromIndex(memory.id)
                archived++
            }
        }

        // Run duplicate merge
        val duplicatesMerged = mergeDuplicates(allMemories)

        val result = DecayResult(
            totalProcessed = allMemories.size,
            decayed = decayed,
            strengthened = strengthened,
            archived = archived,
            duplicatesMerged = duplicatesMerged
        )
        Log.i(TAG, "Decay pass: $result")
        result
    }

    // ─── Score Computation ────────────────────────────────────────────────────

    private fun computeDecayedScore(memory: MemoryEntity, daysSinceAccess: Float): Float {
        val decayRate = when {
            memory.importanceScore >= 0.7f -> DECAY_RATE_SLOW
            memory.importanceScore >= 0.4f -> DECAY_RATE_NORMAL
            else -> DECAY_RATE_FAST
        }

        // Ebbinghaus forgetting curve: R = e^(-t/S)
        val retentionFactor = exp(-decayRate * daysSinceAccess)

        // Access frequency boost — frequently accessed memories decay slower
        val frequencyBoost = min(memory.accessCount * 0.02f, 0.3f)

        val decayed = memory.importanceScore * retentionFactor + frequencyBoost
        return decayed.coerceIn(0f, 1f)
    }

    // ─── Duplicate Detection & Merge ─────────────────────────────────────────

    /**
     * Detect and merge highly similar memories.
     * Keeps the most-accessed/important version, soft-deletes duplicates.
     */
    private suspend fun mergeDuplicates(memories: List<MemoryEntity>): Int {
        if (memories.size < 2) return 0

        val toDelete = mutableSetOf<Long>()
        var merged = 0

        // Only check conversation memories for duplicates (not FAQs/preferences)
        val candidates = memories.filter {
            it.memoryType == MemoryType.CONVERSATION && it.embedding.isNotEmpty()
        }

        for (i in candidates.indices) {
            if (candidates[i].id in toDelete) continue
            for (j in i + 1 until candidates.size) {
                if (candidates[j].id in toDelete) continue

                val similarity = cosineSimilarity(
                    candidates[i].embedding.toFloatArray(),
                    candidates[j].embedding.toFloatArray()
                )

                if (similarity >= DUPLICATE_THRESHOLD) {
                    // Keep the one with higher importance/access count
                    val keep = if (candidates[i].importanceScore >= candidates[j].importanceScore)
                        candidates[i] else candidates[j]
                    val remove = if (keep.id == candidates[i].id) candidates[j] else candidates[i]

                    // Merge access counts into keeper
                    val updatedKeeper = keep.copy(
                        accessCount = keep.accessCount + remove.accessCount,
                        importanceScore = min(keep.importanceScore + 0.05f, 1.0f)
                    )
                    memoryDao.update(updatedKeeper)

                    toDelete.add(remove.id)
                    merged++
                }
            }
        }

        // Batch delete duplicates
        toDelete.forEach { id ->
            memoryDao.deleteById(id)
            hybridSearch.removeFromIndex(id)
        }

        if (merged > 0) Log.i(TAG, "Merged $merged duplicate memories")
        return merged
    }

    // ─── Access Boost ─────────────────────────────────────────────────────────

    /**
     * Strengthen a memory when it is accessed — counteracts decay.
     * Call this whenever a memory is retrieved and used.
     */
    suspend fun boostOnAccess(memoryId: Long) = withContext(Dispatchers.IO) {
        val memory = memoryDao.getById(memoryId) ?: return@withContext
        val newScore = min(memory.importanceScore + ACCESS_BOOST, 1.0f)
        memoryDao.updateImportanceAndAccess(memoryId, newScore)
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Hard-delete all archived (very low importance) old memories.
     * Only called after [runDecayPass] confirms they are truly irrelevant.
     */
    suspend fun cleanupArchived(): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ARCHIVE_AGE_DAYS)
        memoryDao.deleteLowImportanceOlderThan(cutoff, ARCHIVE_THRESHOLD)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isProtected(memory: MemoryEntity): Boolean =
        memory.memoryType in listOf(MemoryType.PREFERENCE, MemoryType.FACT, MemoryType.SUMMARY) ||
            memory.importanceScore > 0.85f

    private fun getFloor(memory: MemoryEntity): Float =
        if (isProtected(memory)) FLOOR_PROTECTED else FLOOR_DEFAULT

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = Math.sqrt(na) * Math.sqrt(nb)
        return if (denom < 1e-8) 0f else (dot / denom).toFloat()
    }

    private fun List<Float>.toFloatArray() = FloatArray(size) { get(it) }
}

data class DecayResult(
    val totalProcessed: Int,
    val decayed: Int,
    val strengthened: Int,
    val archived: Int,
    val duplicatesMerged: Int
)
