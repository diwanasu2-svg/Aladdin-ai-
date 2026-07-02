package com.aladdin.internet.cache

import androidx.room.*

@Dao
interface SearchCacheDao {

    @Query("SELECT * FROM search_cache WHERE queryKey = :key AND expiresAt > :now LIMIT 1")
    suspend fun getValid(key: String, now: Long = System.currentTimeMillis()): SearchCacheEntity?

    @Query("SELECT * FROM search_cache WHERE queryKey = :key LIMIT 1")
    suspend fun getAny(key: String): SearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchCacheEntity)

    @Query("DELETE FROM search_cache WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM search_cache WHERE queryKey = :key")
    suspend fun delete(key: String)

    @Query("SELECT COUNT(*) FROM search_cache")
    suspend fun count(): Int

    @Query("SELECT * FROM search_cache ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<SearchCacheEntity>

    @Query("DELETE FROM search_cache WHERE queryKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM search_cache")
    suspend fun clearAll()
}
