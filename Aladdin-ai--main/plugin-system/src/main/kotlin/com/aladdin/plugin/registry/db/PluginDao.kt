package com.aladdin.plugin.registry.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY installedAtMs DESC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE pluginId = :id")
    suspend fun getById(id: String): PluginEntity?

    @Query("SELECT * FROM plugins WHERE enabled = 1")
    suspend fun getEnabled(): List<PluginEntity>

    @Query("SELECT * FROM plugins WHERE state = :state")
    suspend fun getByState(state: String): List<PluginEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginEntity)

    @Delete
    suspend fun delete(plugin: PluginEntity)

    @Query("DELETE FROM plugins WHERE pluginId = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE plugins SET state = :state, loadError = :error, lastLoadedAtMs = :ts WHERE pluginId = :id")
    suspend fun updateState(id: String, state: String, error: String?, ts: Long)

    @Query("UPDATE plugins SET enabled = :enabled WHERE pluginId = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM plugins")
    suspend fun count(): Int
}
