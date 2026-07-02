package com.aladdin.memory

import com.aladdin.memory.engine.VectorStore
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class VectorStoreTest {

    private lateinit var store: VectorStore

    @Before
    fun setUp() { store = VectorStore() }

    private fun randomVector(size: Int = 384): List<Float> {
        return List(size) { (Math.random() * 2 - 1).toFloat() }
    }

    private fun normalizeVector(v: List<Float>): List<Float> {
        val magnitude = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (magnitude == 0f) v else v.map { it / magnitude }
    }

    @Test fun `empty store returns empty results`() {
        val results = store.search(randomVector(), 5)
        assertThat(results).isEmpty()
    }

    @Test fun `insert and find single vector`() {
        val vec = normalizeVector(randomVector())
        store.upsert(1L, vec)
        val results = store.search(vec, 5)
        assertThat(results).hasSize(1)
        assertThat(results.first().id).isEqualTo(1L)
    }

    @Test fun `identical vectors have highest similarity`() {
        val vec1 = normalizeVector(List(384) { 1.0f })
        val vec2 = normalizeVector(List(384) { 0.5f })
        val query = normalizeVector(List(384) { 1.0f })
        store.upsert(1L, vec1)
        store.upsert(2L, vec2)
        val results = store.search(query, 5)
        assertThat(results.first().id).isEqualTo(1L)
    }

    @Test fun `remove eliminates vector from search`() {
        val vec = normalizeVector(randomVector())
        store.upsert(1L, vec)
        store.upsert(2L, normalizeVector(randomVector()))
        store.remove(1L)
        val results = store.search(vec, 10)
        assertThat(results.none { it.id == 1L }).isTrue()
    }

    @Test fun `size reflects insertions and deletions`() {
        assertThat(store.size).isEqualTo(0)
        store.upsert(1L, normalizeVector(randomVector()))
        store.upsert(2L, normalizeVector(randomVector()))
        assertThat(store.size).isEqualTo(2)
        store.remove(1L)
        assertThat(store.size).isEqualTo(1)
    }

    @Test fun `k limit is respected`() {
        repeat(50) { i -> store.upsert(i.toLong(), normalizeVector(randomVector())) }
        val results = store.search(randomVector(), 10)
        assertThat(results.size).isAtMost(10)
    }

    @Test fun `upsert replaces existing vector`() {
        val vecA = normalizeVector(List(384) { 1.0f })
        val vecB = normalizeVector(List(384) { -1.0f })
        store.upsert(1L, vecA)
        store.upsert(1L, vecB)
        assertThat(store.size).isEqualTo(1)
        val results = store.search(vecB, 5)
        assertThat(results.first().id).isEqualTo(1L)
        assertThat(results.first().score).isGreaterThan(0f)
    }

    @Test fun `clear removes all vectors`() {
        repeat(10) { i -> store.upsert(i.toLong(), normalizeVector(randomVector())) }
        store.clear()
        assertThat(store.size).isEqualTo(0)
        assertThat(store.search(randomVector(), 5)).isEmpty()
    }

    @Test fun `similarity scores are in range 0 to 1 for normalized vectors`() {
        val queryVec = normalizeVector(randomVector())
        repeat(20) { i -> store.upsert(i.toLong(), normalizeVector(randomVector())) }
        val results = store.search(queryVec, 20)
        results.forEach { result ->
            assertThat(result.score).isAtLeast(-1.1f)
            assertThat(result.score).isAtMost(1.1f)
        }
    }

    @Test fun `performance - 1000 vectors searched quickly`() {
        repeat(1000) { i -> store.upsert(i.toLong(), normalizeVector(randomVector())) }
        val start = System.currentTimeMillis()
        repeat(100) { store.search(normalizeVector(randomVector()), 10) }
        val elapsed = System.currentTimeMillis() - start
        assertThat(elapsed).isLessThan(5000L)
    }

    @Test fun `memory limit test - handles large vector counts`() {
        val vectorCount = 5000
        repeat(vectorCount) { i ->
            store.upsert(i.toLong(), normalizeVector(randomVector()))
        }
        assertThat(store.size).isEqualTo(vectorCount)
        val results = store.search(normalizeVector(randomVector()), 10)
        assertThat(results.size).isEqualTo(10)
    }
}
