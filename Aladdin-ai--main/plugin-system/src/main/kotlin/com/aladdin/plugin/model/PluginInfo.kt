package com.aladdin.plugin.model

import com.aladdin.plugin.api.PluginPermissionType

/**
 * Metadata about a discovered / installed plugin.
 * Loaded from plugin.json inside the APK assets.
 */
data class PluginInfo(
    val pluginId: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val apkPath: String,
    val mainClass: String,
    val requiredPermissions: List<PluginPermissionType> = emptyList(),
    val handledCommands: List<String> = emptyList(),
    val minAladdinVersion: String = "1.0.0",
    val tags: List<String> = emptyList(),
    val iconResName: String? = null,
    val state: PluginState = PluginState.DISCOVERED,
    val installedAtMs: Long = System.currentTimeMillis(),
    val lastLoadedAtMs: Long = 0L,
    val loadError: String? = null
)

enum class PluginState {
    DISCOVERED,    // APK found, not yet loaded
    LOADING,       // Currently loading
    ACTIVE,        // Loaded and running
    DISABLED,      // Manually disabled by user
    ERROR,         // Failed to load
    UNLOADING      // Being unloaded (hot-reload or disable)
}
