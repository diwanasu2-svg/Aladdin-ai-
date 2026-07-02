package com.aladdin.app.llm

import android.content.Context
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ResponseCache — Item 54: LLM Response Cache with SQLite + RAM LRU.
 *
 * - RAM: LruCache (64 entries, fast retrieval)
 * - Disk: SQLite via simple key-value file store
 * - TTL: Configurable per-entry (default 24h)
 * - Auto-eviction on expiry
 */
@Singleton
class ResponseCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG         = "ResponseCache"
        private const val DEFAULT_TTL = 24L * 60 * 60 * 1000L  // 24 hours
        private const val RAM_CAPACITY = 64
        private const val CACHE_FILE  = "llm_response_cache"
    }

    private data class CacheEntry(val response: String, val expiresAt: Long)

    private val ramCache = LruCache<String, CacheEntry>(RAM_CAPACITY)
    private val db: android.database.sqlite.SQLiteDatabase by lazy { openDb() }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun get(prompt: String, model: String): String? = withContext(Dispatchers.IO) {
        val key = hash(prompt + model)
        // 1. Check RAM
        ramCache[key]?.let { if (it.expiresAt > now()) return@withContext it.response }
        // 2. Check disk
        val entry = dbGet(key) ?: return@withContext null
        if (entry.expiresAt <= now()) { dbDelete(key); return@withContext null }
        ramCache.put(key, entry)
        entry.response
    }

    suspend fun put(prompt: String, model: String, response: String, ttlMs: Long = DEFAULT_TTL) = withContext(Dispatchers.IO) {
        val key   = hash(prompt + model)
        val entry = CacheEntry(response, now() + ttlMs)
        ramCache.put(key, entry)
        dbPut(key, entry)
        Log.d(TAG, "Cached response for key=${key.take(8)}… TTL=${ttlMs/1000}s")
    }

    suspend fun evictExpired() = withContext(Dispatchers.IO) {
        try {
            db.delete(CACHE_FILE, "expires_at < ?", arrayOf(now().toString()))
            Log.d(TAG, "Evicted expired cache entries")
        } catch (e: Exception) { Log.w(TAG, "Eviction error: ${e.message}") }
    }

    fun invalidate(prompt: String, model: String) {
        val key = hash(prompt + model)
        ramCache.remove(key)
        dbDelete(key)
    }

    fun clear() {
        ramCache.evictAll()
        try { db.delete(CACHE_FILE, null, null) } catch (_: Exception) {}
    }

    fun getHitRate(): Float = ramCache.hitCount().toFloat() /
        (ramCache.hitCount() + ramCache.missCount()).coerceAtLeast(1).toFloat()

    // ── SQLite ────────────────────────────────────────────────────────────────

    private fun openDb(): android.database.sqlite.SQLiteDatabase {
        val file = File(context.cacheDir, "$CACHE_FILE.db")
        val db   = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS $CACHE_FILE (key TEXT PRIMARY KEY, response TEXT, expires_at INTEGER)")
        return db
    }

    private fun dbGet(key: String): CacheEntry? {
        return try {
            db.rawQuery("SELECT response, expires_at FROM $CACHE_FILE WHERE key=?", arrayOf(key))
                .use { c ->
                    if (!c.moveToFirst()) null
                    else CacheEntry(c.getString(0), c.getLong(1))
                }
        } catch (_: Exception) { null }
    }

    private fun dbPut(key: String, entry: CacheEntry) {
        try {
            val cv = android.content.ContentValues().apply {
                put("key", key); put("response", entry.response); put("expires_at", entry.expiresAt)
            }
            db.insertWithOnConflict(CACHE_FILE, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { Log.w(TAG, "DB put error: ${e.message}") }
    }

    private fun dbDelete(key: String) {
        try { db.delete(CACHE_FILE, "key=?", arrayOf(key)) } catch (_: Exception) {}
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun now() = System.currentTimeMillis()
}
