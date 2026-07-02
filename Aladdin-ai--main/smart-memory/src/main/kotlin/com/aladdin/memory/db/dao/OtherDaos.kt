package com.aladdin.memory.db.dao

import androidx.room.*
import com.aladdin.memory.db.entity.*
import kotlinx.coroutines.flow.Flow

// ─── UserProfile ──────────────────────────────────────────────────────────────

@Dao
interface UserProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: UserProfileEntity)

    @Update
    suspend fun update(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfileEntity?>
}

// ─── Contact ──────────────────────────────────────────────────────────────────

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity): Long

    @Update
    suspend fun update(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: Long): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY relationship_score DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY relationship_score DESC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE name LIKE '%' || :query || '%' ORDER BY relationship_score DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 10): List<ContactEntity>

    @Query("UPDATE contacts SET interaction_count = interaction_count + 1, last_interaction = :now, relationship_score = MIN(1.0, relationship_score + 0.05) WHERE id = :id")
    suspend fun recordInteraction(id: Long, now: Long = System.currentTimeMillis())
}

// ─── Project ──────────────────────────────────────────────────────────────────

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE status = :status ORDER BY updated_at DESC")
    fun observeByStatus(status: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("UPDATE projects SET progress = :progress, updated_at = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET status = :status, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, now: Long = System.currentTimeMillis())
}

// ─── Reminder ─────────────────────────────────────────────────────────────────

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE is_done = 0 ORDER BY trigger_at ASC")
    fun observePending(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE is_done = 0 AND trigger_at BETWEEN :from AND :to ORDER BY trigger_at ASC")
    suspend fun getDue(from: Long, to: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE is_done = 0 AND trigger_at <= :now ORDER BY trigger_at ASC")
    suspend fun getOverdue(now: Long = System.currentTimeMillis()): List<ReminderEntity>

    @Query("UPDATE reminders SET is_done = 1 WHERE id = :id")
    suspend fun markDone(id: Long)

    @Query("DELETE FROM reminders WHERE is_done = 1 AND trigger_at < :before")
    suspend fun cleanupCompleted(before: Long): Int
}

// ─── Location ─────────────────────────────────────────────────────────────────

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationEntity): Long

    @Update
    suspend fun update(location: LocationEntity)

    @Delete
    suspend fun delete(location: LocationEntity)

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: Long): LocationEntity?

    @Query("SELECT * FROM locations ORDER BY visit_count DESC")
    fun observeAll(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE name LIKE '%' || :query || '%' OR address LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 10): List<LocationEntity>

    @Query("UPDATE locations SET visit_count = visit_count + 1, last_visit = :now WHERE id = :id")
    suspend fun recordVisit(id: Long, now: Long = System.currentTimeMillis())
}

// ─── Summary ──────────────────────────────────────────────────────────────────

@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: SummaryEntity): Long

    @Query("SELECT * FROM summaries WHERE date_key = :dateKey")
    suspend fun getByDateKey(dateKey: String): SummaryEntity?

    @Query("SELECT * FROM summaries ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<SummaryEntity>

    @Query("SELECT * FROM summaries ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SummaryEntity>>

    @Query("DELETE FROM summaries WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
