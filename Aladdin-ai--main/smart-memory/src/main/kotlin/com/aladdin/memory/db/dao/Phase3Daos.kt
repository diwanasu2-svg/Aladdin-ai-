package com.aladdin.memory.db.dao

import androidx.room.*
import com.aladdin.memory.db.entity.GoalEntity
import com.aladdin.memory.db.entity.HabitEntity
import com.aladdin.memory.db.entity.RelationshipEntity
import kotlinx.coroutines.flow.Flow

// ─── Goal DAO ─────────────────────────────────────────────────────────────────

@Dao
interface GoalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Query("SELECT * FROM goals ORDER BY priority DESC, created_at DESC")
    suspend fun getAll(): List<GoalEntity>

    @Query("SELECT * FROM goals ORDER BY priority DESC, created_at DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE status = :status ORDER BY priority DESC, updated_at DESC")
    suspend fun getByStatus(status: String): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE status = :status ORDER BY priority DESC, updated_at DESC")
    fun observeByStatus(status: String): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE category = :category ORDER BY priority DESC")
    suspend fun getByCategory(category: String): List<GoalEntity>

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM goals WHERE status = 'ACTIVE'")
    fun observeActiveCount(): Flow<Int>

    @Query("DELETE FROM goals WHERE status = 'CANCELLED' AND updated_at < :before")
    suspend fun cleanupCancelled(before: Long): Int
}

// ─── Habit DAO ────────────────────────────────────────────────────────────────

@Dao
interface HabitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habit: HabitEntity): Long

    @Update
    suspend fun update(habit: HabitEntity)

    @Delete
    suspend fun delete(habit: HabitEntity)

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: Long): HabitEntity?

    @Query("SELECT * FROM habits ORDER BY confidence DESC")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits ORDER BY confidence DESC")
    fun observeAll(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE is_confirmed = 1 ORDER BY minute_of_day ASC")
    suspend fun getConfirmed(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE is_confirmed = 1 ORDER BY minute_of_day ASC")
    fun observeConfirmed(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE action_type = :actionType ORDER BY confidence DESC")
    suspend fun getByActionType(actionType: String): List<HabitEntity>

    @Query("""
        SELECT * FROM habits
        WHERE minute_of_day BETWEEN :fromMinute AND :toMinute
        ORDER BY confidence DESC
    """)
    suspend fun getByTimeRange(fromMinute: Int, toMinute: Int): List<HabitEntity>

    @Query("DELETE FROM habits WHERE confidence < :minConfidence AND occurrence_count < :minOccurrences")
    suspend fun cleanupLowConfidence(minConfidence: Float = 0.2f, minOccurrences: Int = 2): Int

    @Query("SELECT COUNT(*) FROM habits WHERE is_confirmed = 1")
    fun observeConfirmedCount(): Flow<Int>
}

// ─── Relationship DAO ─────────────────────────────────────────────────────────

@Dao
interface RelationshipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relationship: RelationshipEntity): Long

    @Update
    suspend fun update(relationship: RelationshipEntity)

    @Delete
    suspend fun delete(relationship: RelationshipEntity)

    @Query("SELECT * FROM relationships WHERE id = :id")
    suspend fun getById(id: Long): RelationshipEntity?

    @Query("SELECT * FROM relationships ORDER BY strength DESC")
    suspend fun getAll(): List<RelationshipEntity>

    @Query("SELECT * FROM relationships ORDER BY strength DESC")
    fun observeAll(): Flow<List<RelationshipEntity>>

    /** All edges where fromName is the source. */
    @Query("SELECT * FROM relationships WHERE from_name LIKE :name || '%' OR from_name LIKE '%' || :name ORDER BY strength DESC")
    suspend fun getOutgoing(name: String): List<RelationshipEntity>

    /** All edges where toName is the target. */
    @Query("SELECT * FROM relationships WHERE to_name LIKE :name || '%' OR to_name LIKE '%' || :name ORDER BY strength DESC")
    suspend fun getIncoming(name: String): List<RelationshipEntity>

    /** Find a specific edge. */
    @Query("""
        SELECT * FROM relationships
        WHERE LOWER(from_name) = LOWER(:fromName)
          AND LOWER(relation_type) = LOWER(:relationType)
          AND LOWER(to_name) = LOWER(:toName)
        LIMIT 1
    """)
    suspend fun find(fromName: String, relationType: String, toName: String): RelationshipEntity?

    @Query("SELECT * FROM relationships WHERE relation_type = :type ORDER BY strength DESC")
    suspend fun getByType(type: String): List<RelationshipEntity>

    @Query("""
        SELECT * FROM relationships
        WHERE LOWER(from_name) LIKE '%' || LOWER(:query) || '%'
           OR LOWER(to_name) LIKE '%' || LOWER(:query) || '%'
           OR LOWER(notes) LIKE '%' || LOWER(:query) || '%'
        ORDER BY strength DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 20): List<RelationshipEntity>

    @Query("SELECT COUNT(*) FROM relationships")
    fun observeCount(): Flow<Int>

    @Query("UPDATE relationships SET strength = MIN(1.0, strength + 0.1), updated_at = :now WHERE id = :id")
    suspend fun increaseStrength(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM relationships WHERE id = :id")
    suspend fun deleteById(id: Long)
}
