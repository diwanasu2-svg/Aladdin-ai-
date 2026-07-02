package com.aladdin.memory

import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.model.SearchFilter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchFilterTest {

    private val now = System.currentTimeMillis()

    private fun makeMemory(
        id: Long = 1L,
        type: String = MemoryType.CONVERSATION,
        createdAt: Long = now,
        importance: Float = 0.5f,
        contactId: Long? = null,
        sessionId: String? = null,
        tags: List<String> = emptyList()
    ) = MemoryEntity(
        id = id,
        content = "Test content",
        memoryType = type,
        createdAt = createdAt,
        importanceScore = importance,
        contactId = contactId,
        sessionId = sessionId,
        tags = tags,
        lastAccessedAt = now
    )

    @Test fun `empty filter matches all`() {
        val filter = SearchFilter()
        assertThat(filter.matches(makeMemory(type = MemoryType.FACT))).isTrue()
        assertThat(filter.matches(makeMemory(type = MemoryType.CONVERSATION))).isTrue()
    }

    @Test fun `type filter matches only specified types`() {
        val filter = SearchFilter(memoryTypes = setOf(MemoryType.FACT))
        assertThat(filter.matches(makeMemory(type = MemoryType.FACT))).isTrue()
        assertThat(filter.matches(makeMemory(type = MemoryType.CONVERSATION))).isFalse()
    }

    @Test fun `fromMs filter excludes old memories`() {
        val filter = SearchFilter(fromMs = now - 1000)
        assertThat(filter.matches(makeMemory(createdAt = now))).isTrue()
        assertThat(filter.matches(makeMemory(createdAt = now - 5000))).isFalse()
    }

    @Test fun `toMs filter excludes future memories`() {
        val filter = SearchFilter(toMs = now)
        assertThat(filter.matches(makeMemory(createdAt = now - 1000))).isTrue()
        assertThat(filter.matches(makeMemory(createdAt = now + 5000))).isFalse()
    }

    @Test fun `importance range filter`() {
        val filter = SearchFilter(minImportance = 0.3f, maxImportance = 0.7f)
        assertThat(filter.matches(makeMemory(importance = 0.5f))).isTrue()
        assertThat(filter.matches(makeMemory(importance = 0.1f))).isFalse()
        assertThat(filter.matches(makeMemory(importance = 0.9f))).isFalse()
    }

    @Test fun `contactId filter`() {
        val filter = SearchFilter(contactId = 42L)
        assertThat(filter.matches(makeMemory(contactId = 42L))).isTrue()
        assertThat(filter.matches(makeMemory(contactId = 99L))).isFalse()
        assertThat(filter.matches(makeMemory(contactId = null))).isFalse()
    }

    @Test fun `sessionId filter`() {
        val filter = SearchFilter(sessionId = "session-abc")
        assertThat(filter.matches(makeMemory(sessionId = "session-abc"))).isTrue()
        assertThat(filter.matches(makeMemory(sessionId = "session-xyz"))).isFalse()
        assertThat(filter.matches(makeMemory(sessionId = null))).isFalse()
    }

    @Test fun `tags filter - any tag match`() {
        val filter = SearchFilter(tags = setOf("ai", "voice"))
        assertThat(filter.matches(makeMemory(tags = listOf("ai", "android")))).isTrue()
        assertThat(filter.matches(makeMemory(tags = listOf("voice")))).isTrue()
        assertThat(filter.matches(makeMemory(tags = listOf("cooking")))).isFalse()
    }

    @Test fun `combined filters all must match`() {
        val filter = SearchFilter(
            memoryTypes = setOf(MemoryType.FACT),
            minImportance = 0.5f,
            tags = setOf("important")
        )
        val matchingMemory = makeMemory(
            type = MemoryType.FACT, importance = 0.8f, tags = listOf("important")
        )
        val mismatchType = makeMemory(
            type = MemoryType.CONVERSATION, importance = 0.8f, tags = listOf("important")
        )
        assertThat(filter.matches(matchingMemory)).isTrue()
        assertThat(filter.matches(mismatchType)).isFalse()
    }

    @Test fun `hasViolations property`() {
        assertThat(SearchFilter().memoryTypes.isEmpty()).isTrue()
    }
}
