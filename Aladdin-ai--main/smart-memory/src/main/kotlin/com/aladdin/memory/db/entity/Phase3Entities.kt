package com.aladdin.memory.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

// ─── Goal ─────────────────────────────────────────────────────────────────────

object GoalStatus {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

/**
 * Cross-session goal — persists across app restarts.
 */
@Entity(
    tableName = "goals",
    indices = [Index("status"), Index("priority"), Index("created_at")]
)
@TypeConverters(Converters::class)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Short title of the goal. */
    val title: String,

    /** Detailed description. */
    val description: String = "",

    /** Ordered list of subtasks/steps. */
    val steps: List<String> = emptyList(),

    /** Steps already completed. */
    @ColumnInfo(name = "completed_steps") val completedSteps: List<String> = emptyList(),

    /** Status: ACTIVE | PAUSED | COMPLETED | CANCELLED */
    val status: String = GoalStatus.ACTIVE,

    /** 0–100 */
    @ColumnInfo(name = "progress_percent") val progressPercent: Int = 0,

    /** 1=low, 2=normal, 3=high, 4=critical */
    val priority: Int = 2,

    /** Optional deadline (epoch ms). */
    @ColumnInfo(name = "due_at_ms") val dueAtMs: Long? = null,

    /** Category: general, work, personal, health, learning, etc. */
    val category: String = "general",

    /** Context to inject on resume (last AI state when paused). */
    @ColumnInfo(name = "resume_context") val resumeContext: String = "",

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ─── Habit ────────────────────────────────────────────────────────────────────

/**
 * Learned user habit/routine.
 */
@Entity(
    tableName = "habits",
    indices = [Index("action_type"), Index("confidence"), Index("is_confirmed")]
)
@TypeConverters(Converters::class)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Action category: routine, reminder, app_launch, location_visit, etc. */
    @ColumnInfo(name = "action_type") val actionType: String,

    /** Human-readable description: "Goes to office at 9 AM" */
    val description: String,

    /** Formatted time pattern: "9:00 AM" */
    @ColumnInfo(name = "time_pattern") val timePattern: String,

    /** Minutes since midnight (0–1439) for clustering. */
    @ColumnInfo(name = "minute_of_day") val minuteOfDay: Int,

    /** Days of week this habit occurs (1=Sun..7=Sat), stored as String list. */
    @ColumnInfo(name = "days_of_week") val daysOfWeek: List<String> = emptyList(),

    /** Optional location context. */
    val location: String? = null,

    /** Number of times this habit was observed. */
    @ColumnInfo(name = "occurrence_count") val occurrenceCount: Int = 1,

    /** 0.0–1.0 confidence score. Promoted to confirmed at ≥0.6. */
    val confidence: Float = 0.3f,

    /** Whether this has been promoted from candidate to confirmed habit. */
    @ColumnInfo(name = "is_confirmed") val isConfirmed: Boolean = false,

    @ColumnInfo(name = "last_observed_at") val lastObservedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ─── Relationship ─────────────────────────────────────────────────────────────

/**
 * A directed edge in the relationship graph.
 * fromName → [relationType] → toName
 */
@Entity(
    tableName = "relationships",
    indices = [
        Index("from_name"),
        Index("to_name"),
        Index("relation_type")
    ]
)
data class RelationshipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Source node name. */
    @ColumnInfo(name = "from_name") val fromName: String,

    /** Source node type: person | organization | location | event | project */
    @ColumnInfo(name = "from_type") val fromType: String = "person",

    /** Edge label: friend_of, works_at, lives_in, reports_to, etc. */
    @ColumnInfo(name = "relation_type") val relationType: String,

    /** Target node name. */
    @ColumnInfo(name = "to_name") val toName: String,

    /** Target node type. */
    @ColumnInfo(name = "to_type") val toType: String = "person",

    /** Optional notes/context about this relationship. */
    val notes: String = "",

    /** 0.0–1.0 relationship strength (increases with interactions). */
    val strength: Float = 0.5f,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
