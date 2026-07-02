package com.aladdin.memory.model

import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType

/** Result of a hybrid search query. */
data class MemorySearchResult(
    val memory: MemoryEntity,
    val score: Float,
    val semanticScore: Float = 0f,
    val bm25Score: Float = 0f
)

/** Search filter applied during hybrid search. */
data class SearchFilter(
    val memoryTypes: Set<String> = emptySet(),       // empty = all types
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val minImportance: Float = 0f,
    val maxImportance: Float = 1f,
    val contactId: Long? = null,
    val sessionId: String? = null,
    val tags: Set<String> = emptySet()               // empty = no tag filter
) {
    fun matches(memory: MemoryEntity): Boolean {
        if (memoryTypes.isNotEmpty() && memory.memoryType !in memoryTypes) return false
        if (fromMs != null && memory.createdAt < fromMs) return false
        if (toMs != null && memory.createdAt > toMs) return false
        if (memory.importanceScore < minImportance) return false
        if (memory.importanceScore > maxImportance) return false
        if (contactId != null && memory.contactId != contactId) return false
        if (sessionId != null && memory.sessionId != sessionId) return false
        if (tags.isNotEmpty() && memory.tags.none { it in tags }) return false
        return true
    }
}

/** Domain model for creating a new memory. */
data class NewMemory(
    val content: String,
    val memoryType: String = MemoryType.CONVERSATION,
    val tags: List<String> = emptyList(),
    val sessionId: String? = null,
    val contactId: Long? = null,
    val source: String = "voice",
    val expiresInDays: Int? = null   // null = never expires
)

/** Stats summary for the memory system. */
data class MemoryStats(
    val totalMemories: Int,
    val averageImportance: Float,
    val vectorStoreSize: Int,
    val bm25IndexSize: Int,
    val oldestMemoryMs: Long?,
    val newestMemoryMs: Long?
)

/** Domain model wrapping MemoryEntity for UI/use-case layers. */
data class Memory(
    val id: Long,
    val content: String,
    val summary: String?,
    val type: String,
    val tags: List<String>,
    val importanceScore: Float,
    val accessCount: Int,
    val createdAt: Long,
    val lastAccessedAt: Long
) {
    companion object {
        fun from(entity: MemoryEntity) = Memory(
            id = entity.id,
            content = entity.content,
            summary = entity.summary,
            type = entity.memoryType,
            tags = entity.tags,
            importanceScore = entity.importanceScore,
            accessCount = entity.accessCount,
            createdAt = entity.createdAt,
            lastAccessedAt = entity.lastAccessedAt
        )
    }
}
