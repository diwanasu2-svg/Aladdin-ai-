package com.aladdin.plugin.model

import com.aladdin.plugin.api.PluginPermissionType

/**
 * Persisted record of a user's consent decision for a plugin permission.
 */
data class PluginPermission(
    val pluginId: String,
    val permission: PluginPermissionType,
    val granted: Boolean,
    val grantedAtMs: Long = System.currentTimeMillis(),
    val revokedAtMs: Long? = null,
    val grantedByUser: Boolean = true    // false = auto-granted (e.g. debug)
)
