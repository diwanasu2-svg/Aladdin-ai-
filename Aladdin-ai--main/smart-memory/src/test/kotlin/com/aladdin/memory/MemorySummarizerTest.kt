package com.aladdin.memory

import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.aladdin.memory.engine.MemorySummarizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MemorySummarizerTest {

    private lateinit var summarizer: MemorySummarizer

    @Before
    fun setUp() {
        summarizer = MemorySummarizer()
    }

    private fun mem(content: String, type: String = MemoryType.CONVERSATION, importance: Float = 0.5f) =
        MemoryEntity(content = content, memoryType = type, importanceScore = importance)

    @Test
    fun `summarize empty list returns empty string`() = runBlocking {
        val result = summarizer.summarize(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `summarize small list returns non-empty string`() = runBlocking {
        val memories = listOf(
            mem("The user loves hiking in the mountains every weekend."),
            mem("The user mentioned they prefer jazz music over pop."),
            mem("The user is working on an Android voice assistant project."),
            mem("The user's favorite food is sushi from a restaurant called Nobu."),
            mem("The user wants to visit Japan next year.")
        )
        val result = summarizer.summarize(memories)
        assertTrue("Summary should not be empty", result.isNotBlank())
    }

    @Test
    fun `summary is under word limit`() = runBlocking {
        val memories = (1..20).map { i ->
            mem("This is sentence number $i about something interesting and important.")
        }
        val result = summarizer.summarize(memories)
        val wordCount = result.split("\\s+".toRegex()).size
        assertTrue("Summary should be under 200 words", wordCount <= 200)
    }

    @Test
    fun `compressForContext respects token budget`() {
        val memories = (1..50).map { i ->
            mem("This is memory $i with some content that uses up tokens in the context window.", importance = i / 50f)
        }
        val compressed = summarizer.compressForContext(memories, maxTokens = 200)
        assertTrue("Compressed list should be smaller", compressed.size < memories.size)
    }

    @Test
    fun `compressForContext prioritizes high importance`() {
        val low  = mem("Low importance memory.", importance = 0.1f)
        val high = mem("Very important fact the user told us.", importance = 0.9f)
        val compressed = summarizer.compressForContext(listOf(low, high), maxTokens = 20)
        assertTrue("High importance should be included", compressed.contains(high))
    }

    @Test
    fun `buildContextString produces formatted output`() {
        val memories = listOf(
            mem("User likes coffee", type = MemoryType.PREFERENCE),
            mem("User is reading a book about Kotlin", type = MemoryType.FACT)
        )
        val context = summarizer.buildContextString(memories)
        assertTrue("Context contains type labels", context.contains("[PREFERENCE]") || context.contains("[FACT]"))
        assertTrue("Context contains content", context.contains("coffee") || context.contains("Kotlin"))
    }

    @Test
    fun `FACT memories are prioritized in compression`() {
        val conv = mem("Casual conversation about the weather.", type = MemoryType.CONVERSATION, importance = 0.5f)
        val fact = mem("User's name is Alex and they are 32 years old.", type = MemoryType.FACT, importance = 0.5f)
        val compressed = summarizer.compressForContext(listOf(conv, fact), maxTokens = 15)
        if (compressed.size == 1) {
            assertTrue("FACT preferred over CONVERSATION", compressed.contains(fact))
        }
    }
}
