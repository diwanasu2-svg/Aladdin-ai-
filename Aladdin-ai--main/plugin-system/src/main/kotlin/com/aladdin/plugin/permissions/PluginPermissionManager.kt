package com.aladdin.plugin.permissions

import android.content.Context
import android.util.Log
import com.aladdin.plugin.api.PluginPermissionType
import com.aladdin.plugin.registry.db.PermissionConsentEntity
import com.aladdin.plugin.registry.db.PluginDatabase
import kotlinx.coroutines.runBlocking

/**
 * Manages plugin permission grants and user consent records.
 *
 * Flow:
 * 1. Plugin declares permissions in plugin.json
 * 2. On first load, [requestPermissions] is called — shows consent UI
 * 3. User approves/denies each permission
 * 4. Grant state is stored in DB and enforced at every [CoreServiceProvider] call
 */
class PluginPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginPermissionManager"
    }

    private val db = PluginDatabase.getInstance(context)
    private val dao = db.permissionConsentDao()

    /** @return true if the plugin currently has the given permission granted */
    fun hasPermission(pluginId: String, permission: PluginPermissionType): Boolean = runBlocking {
        dao.getLatest(pluginId, permission.name)?.granted == true
    }

    /** Grant a permission (called after explicit user approval) */
    suspend fun grant(pluginId: String, permission: PluginPermissionType) {
        Log.i(TAG, "Granting $permission to $pluginId")
        dao.upsert(PermissionConsentEntity(
            pluginId    = pluginId,
            permission  = permission.name,
            granted     = true,
            grantedAtMs = System.currentTimeMillis(),
            revokedAtMs = null
        ))
    }

    /** Revoke a previously granted permission */
    suspend fun revoke(pluginId: String, permission: PluginPermissionType) {
        Log.i(TAG, "Revoking $permission from $pluginId")
        dao.upsert(PermissionConsentEntity(
            pluginId    = pluginId,
            permission  = permission.name,
            granted     = false,
            grantedAtMs = System.currentTimeMillis(),
            revokedAtMs = System.currentTimeMillis()
        ))
    }

    /** Revoke all permissions for a plugin (called on uninstall) */
    suspend fun revokeAll(pluginId: String) {
        dao.deleteForPlugin(pluginId)
        Log.i(TAG, "Revoked all permissions for $pluginId")
    }

    /**
     * Returns all permissions that still need user consent.
     * Call this before loading a plugin to show the consent dialog.
     */
    suspend fun pendingConsent(
        pluginId: String,
        required: List<PluginPermissionType>
    ): List<PluginPermissionType> {
        return required.filter { perm ->
            dao.getLatest(pluginId, perm.name) == null
        }
    }

    /** Returns the full consent record for a plugin (for settings UI) */
    suspend fun getConsentRecord(pluginId: String): Map<PluginPermissionType, Boolean> {
        return dao.getForPlugin(pluginId).associate { entity ->
            val perm = runCatching { PluginPermissionType.valueOf(entity.permission) }
                .getOrNull() ?: return@associate Pair(PluginPermissionType.INTERNET, false)
            perm to entity.granted
        }
    }
}
