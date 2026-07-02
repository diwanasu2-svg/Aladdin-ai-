package com.aladdin.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aladdin.memory.db.MemoryDatabase
import com.aladdin.memory.db.dao.MemoryDao
import com.aladdin.memory.db.entity.MemoryEntity
import com.aladdin.memory.db.entity.MemoryType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: MemoryDao

    @Before
    fun createDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.memoryDao()
    }

    @After
    fun closeDb() { db.close() }

    private fun entity(content: String, type: String = MemoryType.CONVERSATION) = MemoryEntity(
        content = content,
        memoryType = type,
        tags = listOf("test"),
        importanceScore = 0.5f,
        lastAccessedAt = System.currentTimeMillis()
    )

    @Test fun `insert and retrieve memory`() = runTest {
        val id = dao.insert(entity("Hello world memory"))
        val mem = dao.getById(id)
        assertThat(mem).isNotNull()
        assertThat(mem!!.content).isEqualTo("Hello world memory")
    }

    @Test fun `delete memory by id`() = runTest {
        val id = dao.insert(entity("To be deleted"))
        dao.deleteById(id)
        assertThat(dao.getById(id)).isNull()
    }

    @Test fun `getAll returns all inserted memories`() = runTest {
        dao.insert(entity("Memory 1"))
        dao.insert(entity("Memory 2"))
        dao.insert(entity("Memory 3"))
        val all = dao.getAll().first()
        assertThat(all.size).isAtLeast(3)
    }

    @Test fun `insert 100 memories and retrieve all`() = runTest {
        repeat(100) { i -> dao.insert(entity("Memory number $i")) }
        val all = dao.getAll().first()
        assertThat(all.size).isAtLeast(100)
    }

    @Test fun `update importance score`() = runTest {
        val id = dao.insert(entity("Test memory"))
        dao.updateImportanceAndAccess(id, 0.9f, 1, System.currentTimeMillis())
        val updated = dao.getById(id)
        assertThat(updated!!.importanceScore).isEqualTo(0.9f)
        assertThat(updated.accessCount).isEqualTo(1)
    }

    @Test fun `getByType returns only memories of specified type`() = runTest {
        dao.insert(entity("Fact 1", MemoryType.FACT))
        dao.insert(entity("Conversation 1", MemoryType.CONVERSATION))
        dao.insert(entity("Fact 2", MemoryType.FACT))
        val facts = dao.getByType(MemoryType.FACT).first()
        assertThat(facts.all { it.memoryType == MemoryType.FACT }).isTrue()
        assertThat(facts).hasSize(2)
    }

    @Test fun `deleteExpired removes old memories`() = runTest {
        val pastExpiry = System.currentTimeMillis() - 1000
        dao.insert(entity("Expired memory").copy(expiresAt = pastExpiry))
        dao.insert(entity("Valid memory"))
        dao.deleteExpired(System.currentTimeMillis())
        val all = dao.getAll().first()
        assertThat(all.none { it.content == "Expired memory" }).isTrue()
    }

    @Test fun `search by content returns matching results`() = runTest {
        dao.insert(entity("android kotlin development"))
        dao.insert(entity("java spring framework"))
        dao.insert(entity("android studio ide"))
        val results = dao.searchByContent("android")
        assertThat(results.all { it.content.contains("android", ignoreCase = true) }).isTrue()
    }

    @Test fun `count returns correct total`() = runTest {
        val initialCount = dao.count()
        dao.insert(entity("New memory 1"))
        dao.insert(entity("New memory 2"))
        val newCount = dao.count()
        assertThat(newCount).isEqualTo(initialCount + 2)
    }

    @Test fun `getTopByImportance returns ordered results`() = runTest {
        dao.insert(entity("Low importance").copy(importanceScore = 0.1f))
        dao.insert(entity("High importance").copy(importanceScore = 0.9f))
        dao.insert(entity("Medium importance").copy(importanceScore = 0.5f))
        val top = dao.getTopByImportance(10)
        for (i in 0 until top.size - 1) {
            assertThat(top[i].importanceScore).isAtLeast(top[i + 1].importanceScore)
        }
    }

    @Test fun `performance test - 1000 inserts under 10 seconds`() = runTest {
        val start = System.currentTimeMillis()
        repeat(1000) { i ->
            dao.insert(entity("Performance test memory $i", MemoryType.CONVERSATION))
        }
        val elapsed = System.currentTimeMillis() - start
        assertThat(elapsed).isLessThan(10000L)
    }
}
