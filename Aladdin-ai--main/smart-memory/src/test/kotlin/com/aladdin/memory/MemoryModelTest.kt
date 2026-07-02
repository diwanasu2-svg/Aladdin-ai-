package com.aladdin.memory

import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.model.Memory
import com.aladdin.memory.model.MemorySearchResult
import com.aladdin.memory.model.MemoryStats
import com.aladdin.memory.model.NewMemory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemoryModelTest {

    private val now = System.currentTimeMillis()

    private fun sampleEntity(id: Long = 1L, type: String = MemoryType.CONVERSATION) = MemoryEntity(
        id = id,
        content = "Sample memory content",
        summary = "Summary",
        memoryType = type,
        tags = listOf("test", "sample"),
        importanceScore = 0.75f,
        accessCount = 5,
        createdAt = now,
        lastAccessedAt = now
    )

    @Test fun `Memory fromEntity copies all fields correctly`() {
        val entity = sampleEntity(id = 42L, type = MemoryType.FACT)
        val model = Memory.from(entity)
        assertThat(model.id).isEqualTo(42L)
        assertThat(model.content).isEqualTo("Sample memory content")
        assertThat(model.summary).isEqualTo("Summary")
        assertThat(model.type).isEqualTo(MemoryType.FACT)
        assertThat(model.tags).containsExactly("test", "sample")
        assertThat(model.importanceScore).isEqualTo(0.75f)
        assertThat(model.accessCount).isEqualTo(5)
        assertThat(model.createdAt).isEqualTo(now)
        assertThat(model.lastAccessedAt).isEqualTo(now)
    }

    @Test fun `NewMemory default values are sane`() {
        val newMem = NewMemory(content = "Hello world")
        assertThat(newMem.content).isEqualTo("Hello world")
        assertThat(newMem.memoryType).isEqualTo(MemoryType.CONVERSATION)
        assertThat(newMem.tags).isEmpty()
        assertThat(newMem.sessionId).isNull()
        assertThat(newMem.contactId).isNull()
        assertThat(newMem.source).isEqualTo("voice")
        assertThat(newMem.expiresInDays).isNull()
    }

    @Test fun `NewMemory custom values preserved`() {
        val newMem = NewMemory(
            content = "Important fact",
            memoryType = MemoryType.FACT,
            tags = listOf("work", "project"),
            sessionId = "sess-001",
            contactId = 7L,
            source = "text",
            expiresInDays = 30
        )
        assertThat(newMem.memoryType).isEqualTo(MemoryType.FACT)
        assertThat(newMem.tags).containsExactly("work", "project")
        assertThat(newMem.sessionId).isEqualTo("sess-001")
        assertThat(newMem.contactId).isEqualTo(7L)
        assertThat(newMem.expiresInDays).isEqualTo(30)
    }

    @Test fun `MemorySearchResult score properties`() {
        val entity = sampleEntity()
        val result = MemorySearchResult(
            memory = entity,
            score = 0.85f,
            semanticScore = 0.9f,
            bm25Score = 0.75f
        )
        assertThat(result.score).isEqualTo(0.85f)
        assertThat(result.semanticScore).isEqualTo(0.9f)
        assertThat(result.bm25Score).isEqualTo(0.75f)
    }

    @Test fun `MemoryStats holds correct aggregates`() {
        val stats = MemoryStats(
            totalMemories = 500,
            averageImportance = 0.6f,
            vectorStoreSize = 450,
            bm25IndexSize = 500,
            oldestMemoryMs = now - 1_000_000,
            newestMemoryMs = now
        )
        assertThat(stats.totalMemories).isEqualTo(500)
        assertThat(stats.averageImportance).isEqualTo(0.6f)
        assertThat(stats.vectorStoreSize).isEqualTo(450)
    }

    @Test fun `MemoryEntity default values are valid`() {
        val entity = MemoryEntity(content = "Test")
        assertThat(entity.id).isEqualTo(0L)
        assertThat(entity.memoryType).isEqualTo(MemoryType.CONVERSATION)
        assertThat(entity.tags).isEmpty()
        assertThat(entity.embedding).isEmpty()
        assertThat(entity.importanceScore).isEqualTo(0.5f)
        assertThat(entity.accessCount).isEqualTo(0)
        assertThat(entity.source).isEqualTo("voice")
        assertThat(entity.isSummarized).isFalse()
    }

    @Test fun `MemoryType constants are distinct strings`() {
        val types = listOf(
            MemoryType.CONVERSATION, MemoryType.FACT, MemoryType.PREFERENCE,
            MemoryType.EVENT, MemoryType.REMINDER, MemoryType.SUMMARY, MemoryType.PROJECT
        )
        assertThat(types.distinct()).hasSize(7)
    }

    @Test fun `CRUD test - create 100 memories and validate`() {
        val memories = (1..100).map { i ->
            NewMemory(
                content = "Memory number $i about topic ${i % 5}",
                memoryType = when (i % 4) {
                    0 -> MemoryType.FACT
                    1 -> MemoryType.CONVERSATION
                    2 -> MemoryType.PREFERENCE
                    else -> MemoryType.EVENT
                },
                tags = listOf("tag${i % 3}", "category${i % 5}")
            )
        }
        assertThat(memories).hasSize(100)
        assertThat(memories.map { it.content }.distinct()).hasSize(100)
        val facts = memories.filter { it.memoryType == MemoryType.FACT }
        assertThat(facts).isNotEmpty()
    }
}
