package com.aladdin.memory

import com.aladdin.memory.engine.BM25Engine
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class BM25EngineTest {

    private lateinit var engine: BM25Engine

    @Before
    fun setUp() { engine = BM25Engine() }

    @Test fun `empty index returns empty results`() {
        assertThat(engine.search("hello", 10)).isEmpty()
    }

    @Test fun `upsert single doc and find it`() {
        engine.upsert(1L, "The quick brown fox jumps over the lazy dog")
        val results = engine.search("fox", 5)
        assertThat(results).hasSize(1)
        assertThat(results.first().id).isEqualTo(1L)
    }

    @Test fun `higher BM25 score for more relevant doc`() {
        engine.upsert(1L, "machine learning neural network deep learning artificial")
        engine.upsert(2L, "cooking recipe pasta tomato sauce garlic")
        val results = engine.search("machine learning", 5)
        assertThat(results.first().id).isEqualTo(1L)
        assertThat(results.first().score).isGreaterThan(0f)
    }

    @Test fun `remove doc removes it from results`() {
        engine.upsert(1L, "aladdin magic carpet wish")
        engine.upsert(2L, "aladdin genie lamp wish")
        engine.remove(1L)
        val results = engine.search("magic carpet", 5)
        assertThat(results.none { it.id == 1L }).isTrue()
    }

    @Test fun `clear wipes all documents`() {
        engine.upsert(1L, "alpha beta gamma")
        engine.upsert(2L, "delta epsilon zeta")
        engine.clear()
        assertThat(engine.size).isEqualTo(0)
        assertThat(engine.search("alpha", 5)).isEmpty()
    }

    @Test fun `size reflects upsert and remove`() {
        assertThat(engine.size).isEqualTo(0)
        engine.upsert(1L, "hello world")
        assertThat(engine.size).isEqualTo(1)
        engine.upsert(2L, "goodbye world")
        assertThat(engine.size).isEqualTo(2)
        engine.remove(1L)
        assertThat(engine.size).isEqualTo(1)
    }

    @Test fun `upsert same id replaces old document`() {
        engine.upsert(1L, "original content about cats")
        engine.upsert(1L, "updated content about dogs")
        val catResults = engine.search("cats", 5)
        val dogResults = engine.search("dogs", 5)
        assertThat(catResults).isEmpty()
        assertThat(dogResults).hasSize(1)
        assertThat(engine.size).isEqualTo(1)
    }

    @Test fun `stop words are filtered out`() {
        engine.upsert(1L, "this is a test document")
        engine.upsert(2L, "another document about testing frameworks")
        val stopWordResult = engine.search("is a the", 5)
        assertThat(stopWordResult).isEmpty()
    }

    @Test fun `tokenize returns valid tokens`() {
        val tokens = engine.tokenize("Hello World! This is a Test")
        assertThat(tokens).isNotEmpty()
        assertThat(tokens.all { it.length > 2 }).isTrue()
        assertThat(tokens.all { it == it.lowercase() }).isTrue()
    }

    @Test fun `tokenize strips punctuation`() {
        val tokens = engine.tokenize("Hello, World! Testing...")
        assertThat(tokens.none { it.contains(".") || it.contains(",") || it.contains("!") }).isTrue()
    }

    @Test fun `search returns results sorted by score descending`() {
        engine.upsert(1L, "voice assistant speech recognition")
        engine.upsert(2L, "voice assistant voice commands voice control")
        engine.upsert(3L, "random unrelated document")
        val results = engine.search("voice", 5)
        for (i in 0 until results.size - 1) {
            assertThat(results[i].score).isAtLeast(results[i + 1].score)
        }
    }

    @Test fun `searchAmong restricts to candidate set`() {
        engine.upsert(1L, "machine learning model training")
        engine.upsert(2L, "machine learning inference")
        engine.upsert(3L, "machine learning dataset")
        val results = engine.searchAmong("machine learning", setOf(2L, 3L), 10)
        assertThat(results.none { it.id == 1L }).isTrue()
        assertThat(results.map { it.id }).containsAnyOf(2L, 3L)
    }

    @Test fun `multiple docs with custom k1 and b parameters`() {
        val customEngine = BM25Engine(k1 = 1.2f, b = 0.5f)
        customEngine.upsert(1L, "kotlin android development")
        customEngine.upsert(2L, "java android development")
        val results = customEngine.search("kotlin", 5)
        assertThat(results.first().id).isEqualTo(1L)
    }

    @Test fun `performance - 1000 documents indexed and searched quickly`() {
        val start = System.currentTimeMillis()
        repeat(1000) { i ->
            engine.upsert(i.toLong(), "document number $i about topic ${i % 10} and subtopic ${i % 5}")
        }
        val indexed = System.currentTimeMillis()
        val results = engine.search("document topic", 20)
        val searched = System.currentTimeMillis()

        assertThat(indexed - start).isLessThan(5000L)
        assertThat(searched - indexed).isLessThan(500L)
        assertThat(results).isNotEmpty()
    }

    @Test fun `computeTfMap returns term frequencies`() {
        val tfMap = engine.computeTfMap("machine learning machine neural network")
        assertThat(tfMap).containsKey("machin")
    }
}
