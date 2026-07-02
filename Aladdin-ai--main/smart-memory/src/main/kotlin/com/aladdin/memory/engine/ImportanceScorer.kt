package com.aladdin.memory.engine

import com.aladdin.memory.db.entity.MemoryEntity
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * Composite importance scorer.
 *
 * Score = w_freq * frequencyScore
 *       + w_recency * recencyScore
 *       + w_engage * engagementScore
 *       + w_type * typeBonus
 *
 * All components in [0, 1]. Final score clamped to [0, 1].
 *
 * Frequency  — logarithmic access count. More accesses = higher importance.
 * Recency    — exponential decay from last access. Half-life configurable.
 * Engagement — normalized sentiment/importance signals (future: LLM-scored).
 * Type bonus — FACT and PREFERENCE memories get a static boost.
 */
@Singleton
class ImportanceScorer @Inject constructor() {

    companion object {
        // Component weights (must sum to 1.0)
        private const val W_FREQ    = 0.30f
        private const val W_RECENCY = 0.35f
        private const val W_ENGAGE  = 0.20f
        private const val W_TYPE    = 0.15f

        // Recency decay: half-life of 3 days
        private val HALF_LIFE_MS = TimeUnit.DAYS.toMillis(3)

        // Maximum access count for normalization
        private const val MAX_ACCESS_COUNT = 100f
    }

    /**
     * Compute and return the updated importance score for a memory.
     * Does NOT persist the result — call [MemoryDao.updateImportanceAndAccess].
     */
    fun score(memory: MemoryEntity, nowMs: Long = System.currentTimeMillis()): Float {
        val freq    = frequencyScore(memory.accessCount)
        val recency = recencyScore(memory.lastAccessedAt, nowMs)
        val engage  = engagementScore(memory)
        val type    = typeBonus(memory.memoryType)

        val raw = W_FREQ * freq + W_RECENCY * recency + W_ENGAGE * engage + W_TYPE * type
        return raw.coerceIn(0f, 1f)
    }

    /**
     * Score a batch of memories and return (id → score) map.
     */
    fun scoreBatch(memories: List<MemoryEntity>, nowMs: Long = System.currentTimeMillis()): Map<Long, Float> =
        memories.associate { it.id to score(it, nowMs) }

    /**
     * Initial importance score for a new memory (before any access).
     */
    fun initialScore(content: String, memoryType: String): Float {
        val typeB = typeBonus(memoryType)
        val lengthB = lengthBonus(content)
        return (0.4f + typeB * 0.15f + lengthB * 0.05f).coerceIn(0.1f, 0.8f)
    }

    // ─── Component Functions ──────────────────────────────────────────────────

    /** Logarithmic frequency score. */
    private fun frequencyScore(accessCount: Int): Float {
        if (accessCount <= 0) return 0f
        return (ln(accessCount.toFloat() + 1) / ln(MAX_ACCESS_COUNT + 1)).coerceIn(0f, 1f)
    }

    /** Exponential decay recency score. */
    private fun recencyScore(lastAccessedMs: Long, nowMs: Long): Float {
        val elapsedMs = (nowMs - lastAccessedMs).coerceAtLeast(0)
        val halfLifes = elapsedMs.toDouble() / HALF_LIFE_MS
        return exp(-0.693 * halfLifes).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Engagement score: proxy based on memory length and tag richness.
     * In production, this is replaced by LLM-scored salience (0–1).
     */
    private fun engagementScore(memory: MemoryEntity): Float {
        val tagScore = (memory.tags.size.coerceAtMost(5) / 5f) * 0.4f
        val lenScore = lengthBonus(memory.content) * 0.6f
        return (tagScore + lenScore).coerceIn(0f, 1f)
    }

    /** Static bonus based on memory type. */
    private fun typeBonus(type: String): Float = when (type) {
        "FACT"        -> 1.0f
        "PREFERENCE"  -> 0.9f
        "PROJECT"     -> 0.8f
        "EVENT"       -> 0.7f
        "SUMMARY"     -> 0.6f
        "REMINDER"    -> 0.5f
        "CONVERSATION" -> 0.3f
        else          -> 0.3f
    }

    /** Length bonus — longer content is usually more substantive. */
    private fun lengthBonus(content: String): Float {
        val words = content.split("\\s+".toRegex()).size
        return (words.coerceAtMost(200) / 200f)
    }

    /**
     * Determine if a memory should be forgotten based on LRU + importance.
     *
     * Forgetting rule:
     *   - Not accessed in [staleAfterDays] days, AND
     *   - Importance score below [importanceThreshold]
     */
    fun shouldForget(
        memory: MemoryEntity,
        nowMs: Long = System.currentTimeMillis(),
        staleAfterDays: Int = 7,
        importanceThreshold: Float = 0.25f
    ): Boolean {
        val cutoff = nowMs - TimeUnit.DAYS.toMillis(staleAfterDays.toLong())
        return memory.lastAccessedAt < cutoff && memory.importanceScore < importanceThreshold
    }
}
