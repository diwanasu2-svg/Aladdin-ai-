package com.aladdin.plugin.registry.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PluginEntity::class, PermissionConsentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PluginDatabase : RoomDatabase() {
    abstract fun pluginDao(): PluginDao
    abstract fun permissionConsentDao(): PermissionConsentDao

    companion object {
        @Volatile private var INSTANCE: PluginDatabase? = null

        fun getInstance(context: Context): PluginDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PluginDatabase::class.java,
                    "aladdin_plugins.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

@Entity(tableName = "permission_consents")
data class PermissionConsentEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pluginId: String,
    val permission: String,
    val granted: Boolean,
    val grantedAtMs: Long,
    val revokedAtMs: Long?
)

@Dao
interface PermissionConsentDao {
    @androidx.room.Query("SELECT * FROM permission_consents WHERE pluginId = :pluginId")
    suspend fun getForPlugin(pluginId: String): List<PermissionConsentEntity>

    @androidx.room.Query("SELECT * FROM permission_consents WHERE pluginId = :pluginId AND permission = :perm ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(pluginId: String, perm: String): PermissionConsentEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PermissionConsentEntity)

    @androidx.room.Query("DELETE FROM permission_consents WHERE pluginId = :pluginId")
    suspend fun deleteForPlugin(pluginId: String)
}
