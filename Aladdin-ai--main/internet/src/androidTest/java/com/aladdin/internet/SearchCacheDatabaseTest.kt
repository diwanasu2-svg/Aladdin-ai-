package com.aladdin.internet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aladdin.internet.cache.SearchCacheDatabase
import com.aladdin.internet.cache.SearchCacheDao
import com.aladdin.internet.cache.SearchCacheEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchCacheDatabaseTest {

    private lateinit var db: SearchCacheDatabase
    private lateinit var dao: SearchCacheDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SearchCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.searchCacheDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun cacheEntry(query: String, responseJson: String = "{}") = SearchCacheEntity(
        queryHash = query.hashCode().toString(),
        query = query,
        responseJson = responseJson,
        expiresAt = System.currentTimeMillis() + 3600_000L,
        createdAt = System.currentTimeMillis()
    )

    @Test fun `insert and retrieve cache entry`() = runTest {
        dao.upsert(cacheEntry("kotlin android"))
        val entry = dao.getByQueryHash("kotlin android".hashCode().toString())
        assertThat(entry).isNotNull()
        assertThat(entry!!.query).isEqualTo("kotlin android")
    }

    @Test fun `upsert replaces existing entry`() = runTest {
        dao.upsert(cacheEntry("query", "old response"))
        dao.upsert(cacheEntry("query", "new response"))
        val entry = dao.getByQueryHash("query".hashCode().toString())
        assertThat(entry!!.responseJson).isEqualTo("new response")
    }

    @Test fun `deleteExpired removes stale entries`() = runTest {
        val expired = cacheEntry("expired").copy(expiresAt = System.currentTimeMillis() - 1000L)
        val valid = cacheEntry("valid")
        dao.upsert(expired)
        dao.upsert(valid)
        dao.deleteExpired(System.currentTimeMillis())
        assertThat(dao.getByQueryHash("expired".hashCode().toString())).isNull()
        assertThat(dao.getByQueryHash("valid".hashCode().toString())).isNotNull()
    }

    @Test fun `clear removes all entries`() = runTest {
        repeat(10) { i -> dao.upsert(cacheEntry("query$i")) }
        dao.clearAll()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test fun `count returns correct number`() = runTest {
        assertThat(dao.count()).isEqualTo(0)
        dao.upsert(cacheEntry("q1"))
        dao.upsert(cacheEntry("q2"))
        assertThat(dao.count()).isEqualTo(2)
    }

    @Test fun `getRecent returns entries ordered by creation time`() = runTest {
        (1..5).forEach { i ->
            dao.upsert(cacheEntry("query$i").copy(createdAt = i.toLong() * 1000L))
        }
        val recent = dao.getRecent(3)
        assertThat(recent).hasSize(3)
        assertThat(recent[0].createdAt).isAtLeast(recent[1].createdAt)
    }

    @Test fun `performance - 100 cache inserts and lookups`() = runTest {
        val start = System.currentTimeMillis()
        repeat(100) { i ->
            dao.upsert(cacheEntry("query$i", """{"results": []}"""))
        }
        repeat(100) { i ->
            dao.getByQueryHash("query$i".hashCode().toString())
        }
        val elapsed = System.currentTimeMillis() - start
        assertThat(elapsed).isLessThan(5000L)
    }
}
