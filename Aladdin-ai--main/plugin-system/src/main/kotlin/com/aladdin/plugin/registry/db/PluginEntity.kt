package com.aladdin.plugin.registry.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aladdin.plugin.model.PluginState

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val pluginId: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val apkPath: String,
    val mainClass: String,
    val requiredPermissions: String,   // JSON array string
    val handledCommands: String,       // JSON array string
    val minAladdinVersion: String,
    val tags: String,                  // JSON array string
    val iconResName: String?,
    val state: String,                 // PluginState.name()
    val installedAtMs: Long,
    val lastLoadedAtMs: Long,
    val loadError: String?,
    val enabled: Boolean = true
)
