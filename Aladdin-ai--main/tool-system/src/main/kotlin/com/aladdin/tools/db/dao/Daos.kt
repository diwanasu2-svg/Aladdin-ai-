package com.aladdin.tools.db.dao

import androidx.room.*
import com.aladdin.tools.db.entity.*
import kotlinx.coroutines.flow.Flow

// ─── Notes DAO ────────────────────────────────────────────────────────────────

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(note: NoteEntity): Long
    @Update suspend fun update(note: NoteEntity)
    @Delete suspend fun delete(note: NoteEntity)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC") fun observeAll(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun getById(id: Long): NoteEntity?
    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC") suspend fun getAll(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    suspend fun search(q: String): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE isVoiceNote = 1") suspend fun getVoiceNotes(): List<NoteEntity>
    @Query("UPDATE notes SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPin(id: Long, pinned: Boolean, now: Long = System.currentTimeMillis())
}

// ─── ToDo DAO ─────────────────────────────────────────────────────────────────

@Dao
interface TodoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(todo: TodoEntity): Long
    @Update suspend fun update(todo: TodoEntity)
    @Delete suspend fun delete(todo: TodoEntity)
    @Query("DELETE FROM todos WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("SELECT * FROM todos ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END, dueAt ASC NULLS LAST")
    fun observeAll(): Flow<List<TodoEntity>>
    @Query("SELECT * FROM todos WHERE listName = :list ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END")
    suspend fun getByList(list: String): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE status != 'DONE' AND status != 'CANCELLED'") suspend fun getPending(): List<TodoEntity>
    @Query("SELECT * FROM todos WHERE dueAt IS NOT NULL AND dueAt < :now AND status = 'PENDING'") suspend fun getOverdue(now: Long = System.currentTimeMillis()): List<TodoEntity>
    @Query("UPDATE todos SET status = 'DONE', completedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun markDone(id: Long, now: Long = System.currentTimeMillis())
    @Query("SELECT * FROM todos WHERE id = :id") suspend fun getById(id: Long): TodoEntity?
    @Query("SELECT DISTINCT listName FROM todos") suspend fun getLists(): List<String>
    @Query("DELETE FROM todos WHERE status = 'DONE' AND completedAt < :before") suspend fun cleanupCompleted(before: Long): Int
}

// ─── Alarm DAO ────────────────────────────────────────────────────────────────

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(alarm: AlarmEntity): Long
    @Update suspend fun update(alarm: AlarmEntity)
    @Delete suspend fun delete(alarm: AlarmEntity)
    @Query("DELETE FROM alarms WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("SELECT * FROM alarms ORDER BY triggerAt ASC") fun observeAll(): Flow<List<AlarmEntity>>
    @Query("SELECT * FROM alarms WHERE isActive = 1 ORDER BY triggerAt ASC") suspend fun getActive(): List<AlarmEntity>
    @Query("SELECT * FROM alarms WHERE id = :id") suspend fun getById(id: Long): AlarmEntity?
    @Query("UPDATE alarms SET isActive = :active WHERE id = :id") suspend fun setActive(id: Long, active: Boolean)
}

// ─── Clipboard DAO ────────────────────────────────────────────────────────────

@Dao
interface ClipboardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entry: ClipboardEntry): Long
    @Query("SELECT * FROM clipboard_history ORDER BY createdAt DESC LIMIT :limit") suspend fun getHistory(limit: Int = 50): List<ClipboardEntry>
    @Query("DELETE FROM clipboard_history WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM clipboard_history WHERE createdAt < :before") suspend fun clearBefore(before: Long): Int
    @Query("DELETE FROM clipboard_history") suspend fun clearAll(): Int
    @Query("SELECT * FROM clipboard_history WHERE text LIKE '%' || :q || '%' ORDER BY createdAt DESC") suspend fun search(q: String): List<ClipboardEntry>
}

// ─── Timer DAO ────────────────────────────────────────────────────────────────

@Dao
interface TimerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(timer: TimerEntity): Long
    @Update suspend fun update(timer: TimerEntity)
    @Delete suspend fun delete(timer: TimerEntity)
    @Query("SELECT * FROM timers ORDER BY createdAt DESC") fun observeAll(): Flow<List<TimerEntity>>
    @Query("SELECT * FROM timers WHERE id = :id") suspend fun getById(id: Long): TimerEntity?
    @Query("SELECT * FROM timers WHERE state = 'RUNNING'") suspend fun getRunning(): List<TimerEntity>
    @Query("UPDATE timers SET state = :state, remainingMs = :remaining WHERE id = :id")
    suspend fun updateState(id: Long, state: String, remaining: Long)
}
