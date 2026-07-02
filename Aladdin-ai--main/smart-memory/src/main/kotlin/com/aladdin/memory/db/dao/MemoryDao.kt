package com.aladdin.memory.db.dao

import androidx.room.*
import com.aladdin.memory.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ─── Insert / Update / Delete ─────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<MemoryEntity>): List<Long>

    @Update
    suspend fun update(memory: MemoryEntity)

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories WHERE expires_at IS NOT NULL AND expires_at < :now")
    suspend fun deleteExpired(now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM memories WHERE created_at < :cutoffMs AND importance_score < :maxImportance")
    suspend fun deleteLowImportanceOlderThan(cutoffMs: Long, maxImportance: Float = 0.3f): Int

    // ─── Reads ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id")
    fun observeById(id: Long): Flow<MemoryEntity?>

    @Query("SELECT * FROM memories ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecent(limit: Int = 50, offset: Int = 0): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY created_at DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE memory_type = :type ORDER BY importance_score DESC")
    suspend fun getByType(type: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE memory_type = :type ORDER BY importance_score DESC")
    fun observeByType(type: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun getBySession(sessionId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE contact_id = :contactId ORDER BY created_at DESC")
    suspend fun getByContact(contactId: Long): List<MemoryEntity>

    // ─── BM25 / Keyword Search ────────────────────────────────────────────────

    /**
     * Full-text search using LIKE – good enough for most cases.
     * Replace with FTS5 virtual table for production at scale.
     */
    @Query("""
        SELECT * FROM memories
        WHERE (content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%')
        ORDER BY importance_score DESC
        LIMIT :limit
    """)
    suspend fun searchByKeyword(query: String, limit: Int = 20): List<MemoryEntity>

    @Query("""
        SELECT * FROM memories
        WHERE (content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%')
          AND memory_type = :type
        ORDER BY importance_score DESC
        LIMIT :limit
    """)
    suspend fun searchByKeywordAndType(query: String, type: String, limit: Int = 20): List<MemoryEntity>

    // ─── Importance / Ranking ─────────────────────────────────────────────────

    @Query("SELECT * FROM memories ORDER BY importance_score DESC LIMIT :limit")
    suspend fun getTopImportant(limit: Int = 20): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY importance_score DESC LIMIT :limit")
    fun observeTopImportant(limit: Int = 20): Flow<List<MemoryEntity>>

    @Query("UPDATE memories SET importance_score = :score, last_accessed_at = :now, access_count = access_count + 1 WHERE id = :id")
    suspend fun updateImportanceAndAccess(id: Long, score: Float, now: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET access_count = access_count + 1, last_accessed_at = :now WHERE id IN (:ids)")
    suspend fun bumpAccessCount(ids: List<Long>, now: Long = System.currentTimeMillis())

    // ─── Summarization ────────────────────────────────────────────────────────

    @Query("SELECT * FROM memories WHERE is_summarized = 0 AND created_at < :before ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnsummarized(before: Long, limit: Int = 100): List<MemoryEntity>

    @Query("UPDATE memories SET is_summarized = 1 WHERE id IN (:ids)")
    suspend fun markSummarized(ids: List<Long>)

    // ─── Vector candidate fetch ────────────────────────────────────────────────
    // We fetch recent / important candidates then do in-memory cosine similarity

    @Query("""
        SELECT * FROM memories
        WHERE importance_score >= :minImportance
          AND (expires_at IS NULL OR expires_at > :now)
        ORDER BY importance_score DESC, last_accessed_at DESC
        LIMIT :limit
    """)
    suspend fun getCandidatesForVectorSearch(
        minImportance: Float = 0.0f,
        now: Long = System.currentTimeMillis(),
        limit: Int = 2000
    ): List<MemoryEntity>

    // ─── Stats ────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM memories")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getCount(): Int

    @Query("SELECT AVG(importance_score) FROM memories")
    suspend fun getAverageImportance(): Float
}
