package com.aladdin.memory

import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.engine.ImportanceScorer
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ImportanceScorerTest {

    private lateinit var scorer: ImportanceScorer
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() { scorer = ImportanceScorer() }

    private fun makeMemory(
        id: Long = 1L,
        content: String = "Test memory content here",
        type: String = MemoryType.CONVERSATION,
        accessCount: Int = 0,
        lastAccessedAt: Long = now,
        importanceScore: Float = 0.5f,
        tags: List<String> = emptyList()
    ) = MemoryEntity(
        id = id,
        content = content,
        memoryType = type,
        accessCount = accessCount,
        lastAccessedAt = lastAccessedAt,
        importanceScore = importanceScore,
        tags = tags
    )

    @Test fun `score is in range 0 to 1`() {
        val score = scorer.score(makeMemory(), now)
        assertThat(score).isAtLeast(0f)
        assertThat(score).isAtMost(1f)
    }

    @Test fun `FACT type scores higher than CONVERSATION type`() {
        val factMemory = makeMemory(type = MemoryType.FACT, accessCount = 5)
        val convMemory = makeMemory(type = MemoryType.CONVERSATION, accessCount = 5)
        assertThat(scorer.score(factMemory, now)).isGreaterThan(scorer.score(convMemory, now))
    }

    @Test fun `recently accessed memory scores higher than stale memory`() {
        val recentMemory = makeMemory(lastAccessedAt = now)
        val staleMemory = makeMemory(lastAccessedAt = now - TimeUnit.DAYS.toMillis(30))
        assertThat(scorer.score(recentMemory, now)).isGreaterThan(scorer.score(staleMemory, now))
    }

    @Test fun `higher access count increases score`() {
        val lowAccess = makeMemory(accessCount = 0)
        val highAccess = makeMemory(accessCount = 50)
        assertThat(scorer.score(highAccess, now)).isGreaterThan(scorer.score(lowAccess, now))
    }

    @Test fun `memory with more tags scores higher`() {
        val noTags = makeMemory(tags = emptyList())
        val manyTags = makeMemory(tags = listOf("ai", "voice", "memory", "android", "kotlin"))
        assertThat(scorer.score(manyTags, now)).isGreaterThan(scorer.score(noTags, now))
    }

    @Test fun `scoreBatch returns correct count`() {
        val memories = (1..10).map { makeMemory(id = it.toLong(), accessCount = it) }
        val scores = scorer.scoreBatch(memories, now)
        assertThat(scores).hasSize(10)
        scores.values.forEach { score ->
            assertThat(score).isAtLeast(0f)
            assertThat(score).isAtMost(1f)
        }
    }

    @Test fun `initialScore returns value in valid range`() {
        val score = scorer.initialScore("Some important memory content about the user", MemoryType.FACT)
        assertThat(score).isAtLeast(0.1f)
        assertThat(score).isAtMost(0.8f)
    }

    @Test fun `initialScore FACT type higher than CONVERSATION`() {
        val factScore = scorer.initialScore("content", MemoryType.FACT)
        val convScore = scorer.initialScore("content", MemoryType.CONVERSATION)
        assertThat(factScore).isGreaterThan(convScore)
    }

    @Test fun `shouldForget stale low-importance memory`() {
        val staleMemory = makeMemory(
            lastAccessedAt = now - TimeUnit.DAYS.toMillis(10),
            importanceScore = 0.1f
        )
        assertThat(scorer.shouldForget(staleMemory, now)).isTrue()
    }

    @Test fun `shouldForget - keep recent low-importance memory`() {
        val recentMemory = makeMemory(
            lastAccessedAt = now - TimeUnit.DAYS.toMillis(1),
            importanceScore = 0.1f
        )
        assertThat(scorer.shouldForget(recentMemory, now)).isFalse()
    }

    @Test fun `shouldForget - keep stale high-importance memory`() {
        val importantMemory = makeMemory(
            lastAccessedAt = now - TimeUnit.DAYS.toMillis(10),
            importanceScore = 0.9f
        )
        assertThat(scorer.shouldForget(importantMemory, now)).isFalse()
    }

    @Test fun `type bonuses follow priority order`() {
        val types = listOf(
            MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.PROJECT,
            MemoryType.EVENT, MemoryType.SUMMARY, MemoryType.REMINDER, MemoryType.CONVERSATION
        )
        val scores = types.map { scorer.initialScore("short content here", it) }
        assertThat(scores.first()).isGreaterThan(scores.last())
    }

    @Test fun `long content gets higher initial score than short content`() {
        val shortScore = scorer.initialScore("Hi", MemoryType.CONVERSATION)
        val longScore = scorer.initialScore("This is a very detailed and comprehensive memory about an important event that happened today with lots of context and nuance", MemoryType.CONVERSATION)
        assertThat(longScore).isAtLeast(shortScore)
    }
}
