package com.aladdin.tools.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─── Type Converters ──────────────────────────────────────────────────────────

class ToolTypeConverters {
    private val gson = Gson()

    @TypeConverter fun toPriority(v: String) = TaskPriority.valueOf(v)
    @TypeConverter fun fromPriority(v: TaskPriority) = v.name
    @TypeConverter fun toTaskStatus(v: String) = TaskStatus.valueOf(v)
    @TypeConverter fun fromTaskStatus(v: TaskStatus) = v.name
    @TypeConverter fun toTimerState(v: String) = TimerState.valueOf(v)
    @TypeConverter fun fromTimerState(v: TimerState) = v.name

    // Fix 17: converters for List<String> tags
    @TypeConverter fun fromStringList(v: List<String>): String = gson.toJson(v)
    @TypeConverter fun toStringList(v: String): List<String> =
        gson.fromJson(v, object : TypeToken<List<String>>() {}.type) ?: emptyList()
}

// ─── Notes ────────────────────────────────────────────────────────────────────

@Entity(tableName = "notes")
@TypeConverters(ToolTypeConverters::class)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val isVoiceNote: Boolean = false,
    val audioPath: String? = null,
    // Fix 17: changed from String (comma-separated) to List<String>
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ─── To-Do ────────────────────────────────────────────────────────────────────

enum class TaskPriority { LOW, NORMAL, HIGH, CRITICAL }
enum class TaskStatus   { PENDING, IN_PROGRESS, DONE, CANCELLED }

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val status: TaskStatus = TaskStatus.PENDING,
    val listName: String = "default",
    val dueAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ─── Alarm ────────────────────────────────────────────────────────────────────

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val triggerAt: Long,
    val isRecurring: Boolean = false,
    val repeatDays: String = "",     // "MON,WED,FRI" or empty
    val isActive: Boolean = true,
    val snoozeMinutes: Int = 5,
    val vibrate: Boolean = true,
    val ringtone: String = "default",
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Clipboard History ────────────────────────────────────────────────────────

@Entity(tableName = "clipboard_history")
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String = "unknown",
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Timers ───────────────────────────────────────────────────────────────────

enum class TimerState { IDLE, RUNNING, PAUSED, FINISHED }

@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val durationMs: Long,
    val remainingMs: Long,
    val state: TimerState = TimerState.IDLE,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val pausedAt: Long? = null
)
