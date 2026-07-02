package com.aladdin.memory.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─── Type Converters ──────────────────────────────────────────────────────────

class Converters {
    private val gson = Gson()

    @TypeConverter fun fromStringList(v: List<String>): String = gson.toJson(v)
    @TypeConverter fun toStringList(v: String): List<String> =
        gson.fromJson(v, object : TypeToken<List<String>>() {}.type) ?: emptyList()

    @TypeConverter fun fromFloatList(v: List<Float>): String = gson.toJson(v)
    @TypeConverter fun toFloatList(v: String): List<Float> =
        gson.fromJson(v, object : TypeToken<List<Float>>() {}.type) ?: emptyList()

    @TypeConverter fun fromStringMap(v: Map<String, String>): String = gson.toJson(v)
    @TypeConverter fun toStringMap(v: String): Map<String, String> =
        gson.fromJson(v, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
}

// ─── Memory ───────────────────────────────────────────────────────────────────

/**
 * Core memory entry. Each conversation turn, insight, or fact is stored here.
 */
@Entity(
    tableName = "memories",
    indices = [Index("created_at"), Index("importance_score"), Index("memory_type")]
)
@TypeConverters(Converters::class)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Raw text content of the memory. */
    @ColumnInfo(name = "content") val content: String,

    /** Compressed/summarized form of content (set after summarization). */
    @ColumnInfo(name = "summary") val summary: String? = null,

    /** Memory category: CONVERSATION, FACT, PREFERENCE, EVENT, REMINDER, SUMMARY */
    @ColumnInfo(name = "memory_type") val memoryType: String = MemoryType.CONVERSATION,

    /** Tags for BM25 / keyword search. */
    @ColumnInfo(name = "tags") val tags: List<String> = emptyList(),

    /** Sentence-BERT embedding vector (384 dims for MiniLM). Stored as JSON float array. */
    @ColumnInfo(name = "embedding") val embedding: List<Float> = emptyList(),

    /** Composite importance score: frequency + recency + engagement. 0.0–1.0 */
    @ColumnInfo(name = "importance_score") val importanceScore: Float = 0.5f,

    /** Number of times this memory was retrieved/used. */
    @ColumnInfo(name = "access_count") val accessCount: Int = 0,

    /** Last time this memory was accessed (epoch ms). */
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long = System.currentTimeMillis(),

    /** Creation timestamp (epoch ms). */
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),

    /** Soft-delete / expiry timestamp (epoch ms). Null = never expires. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,

    /** Optional reference to a related contact. */
    @ColumnInfo(name = "contact_id") val contactId: Long? = null,

    /** Session/conversation ID this memory belongs to. */
    @ColumnInfo(name = "session_id") val sessionId: String? = null,

    /** Optional source metadata (e.g. "voice", "text", "import"). */
    @ColumnInfo(name = "source") val source: String = "voice",

    /** Whether this memory has been summarized into a SUMMARY entry. */
    @ColumnInfo(name = "is_summarized") val isSummarized: Boolean = false,

    /** BM25 term frequency map (stored as JSON). */
    @ColumnInfo(name = "tf_map") val tfMap: Map<String, String> = emptyMap()
)

object MemoryType {
    const val CONVERSATION = "CONVERSATION"
    const val FACT = "FACT"
    const val PREFERENCE = "PREFERENCE"
    const val EVENT = "EVENT"
    const val REMINDER = "REMINDER"
    const val SUMMARY = "SUMMARY"
    const val PROJECT = "PROJECT"
}

// ─── User Profile ─────────────────────────────────────────────────────────────

@Entity(tableName = "user_profile")
@TypeConverters(Converters::class)
data class UserProfileEntity(
    @PrimaryKey val id: Long = 1, // singleton row
    val name: String = "",
    val age: Int? = null,
    val timezone: String = "UTC",
    @ColumnInfo(name = "favorite_topics") val favoriteTopics: List<String> = emptyList(),
    @ColumnInfo(name = "favorite_music") val favoriteMusic: List<String> = emptyList(),
    @ColumnInfo(name = "favorite_foods") val favoriteFoods: List<String> = emptyList(),
    @ColumnInfo(name = "disliked_topics") val dislikedTopics: List<String> = emptyList(),
    @ColumnInfo(name = "language") val language: String = "en",
    @ColumnInfo(name = "voice_name") val voiceName: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ─── Contact ──────────────────────────────────────────────────────────────────

@Entity(tableName = "contacts", indices = [Index("name"), Index("relationship_score")])
@TypeConverters(Converters::class)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "relationship_type") val relationshipType: String = "acquaintance",
    val notes: String = "",
    @ColumnInfo(name = "contact_info") val contactInfo: Map<String, String> = emptyMap(),
    /** 0.0–1.0 — increases with interaction frequency and depth */
    @ColumnInfo(name = "relationship_score") val relationshipScore: Float = 0.1f,
    @ColumnInfo(name = "interaction_count") val interactionCount: Int = 0,
    @ColumnInfo(name = "last_interaction") val lastInteraction: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "birthday") val birthday: String? = null,
    @ColumnInfo(name = "tags") val tags: List<String> = emptyList(),
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ─── Project ──────────────────────────────────────────────────────────────────

@Entity(tableName = "projects", indices = [Index("status")])
@TypeConverters(Converters::class)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val status: String = ProjectStatus.ACTIVE,
    /** 0–100 */
    val progress: Int = 0,
    val tags: List<String> = emptyList(),
    @ColumnInfo(name = "due_date") val dueDate: Long? = null,
    @ColumnInfo(name = "milestones") val milestones: List<String> = emptyList(),
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

object ProjectStatus {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val ARCHIVED = "ARCHIVED"
}

// ─── Reminder ─────────────────────────────────────────────────────────────────

@Entity(tableName = "reminders", indices = [Index("trigger_at"), Index("is_done")])
@TypeConverters(Converters::class)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String = "",
    @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    @ColumnInfo(name = "is_done") val isDone: Boolean = false,
    @ColumnInfo(name = "repeat_interval_ms") val repeatIntervalMs: Long? = null,
    @ColumnInfo(name = "contact_id") val contactId: Long? = null,
    @ColumnInfo(name = "tags") val tags: List<String> = emptyList(),
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ─── Location ─────────────────────────────────────────────────────────────────

@Entity(tableName = "locations", indices = [Index("name")])
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val category: String = "general",
    @ColumnInfo(name = "visit_count") val visitCount: Int = 0,
    @ColumnInfo(name = "last_visit") val lastVisit: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ─── Summary ──────────────────────────────────────────────────────────────────

@Entity(tableName = "summaries", indices = [Index("date_key")])
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Date string "YYYY-MM-DD" or "YYYY-WW" */
    @ColumnInfo(name = "date_key") val dateKey: String,
    val content: String,
    @ColumnInfo(name = "memory_count") val memoryCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
