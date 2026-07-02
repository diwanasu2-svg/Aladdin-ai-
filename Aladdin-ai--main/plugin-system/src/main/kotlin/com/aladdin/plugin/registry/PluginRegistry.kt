package com.aladdin.plugin.registry

import android.content.Context
import com.aladdin.plugin.api.PluginPermissionType
import com.aladdin.plugin.model.PluginInfo
import com.aladdin.plugin.model.PluginState
import com.aladdin.plugin.registry.db.PluginDatabase
import com.aladdin.plugin.registry.db.PluginEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * Persistent registry for all known plugins.
 * Backed by Room — single source of truth for plugin metadata.
 */
class PluginRegistry(context: Context) {

    private val db = PluginDatabase.getInstance(context)
    private val dao = db.pluginDao()

    val allPlugins: Flow<List<PluginInfo>> =
        dao.observeAll().map { entities -> entities.map { it.toInfo() } }

    suspend fun register(info: PluginInfo) = dao.upsert(info.toEntity())

    suspend fun unregister(pluginId: String) = dao.deleteById(pluginId)

    suspend fun get(pluginId: String): PluginInfo? = dao.getById(pluginId)?.toInfo()

    suspend fun getEnabled(): List<PluginInfo> = dao.getEnabled().map { it.toInfo() }

    suspend fun updateState(pluginId: String, state: PluginState, error: String? = null) =
        dao.updateState(pluginId, state.name, error, System.currentTimeMillis())

    suspend fun setEnabled(pluginId: String, enabled: Boolean) =
        dao.setEnabled(pluginId, enabled)

    suspend fun count(): Int = dao.count()

    // ─── Mapping ──────────────────────────────────────────────────────────────

    private fun PluginInfo.toEntity() = PluginEntity(
        pluginId            = pluginId,
        name                = name,
        version             = version,
        description         = description,
        author              = author,
        apkPath             = apkPath,
        mainClass           = mainClass,
        requiredPermissions = JSONArray(requiredPermissions.map { it.name }).toString(),
        handledCommands     = JSONArray(handledCommands).toString(),
        minAladdinVersion   = minAladdinVersion,
        tags                = JSONArray(tags).toString(),
        iconResName         = iconResName,
        state               = state.name,
        installedAtMs       = installedAtMs,
        lastLoadedAtMs      = lastLoadedAtMs,
        loadError           = loadError,
        enabled             = state != PluginState.DISABLED
    )

    private fun PluginEntity.toInfo(): PluginInfo {
        val perms = runCatching {
            val arr = JSONArray(requiredPermissions)
            (0 until arr.length()).mapNotNull { runCatching { PluginPermissionType.valueOf(arr.getString(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
        val cmds = runCatching {
            val arr = JSONArray(handledCommands)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        val tagList = runCatching {
            val arr = JSONArray(tags)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        return PluginInfo(
            pluginId            = pluginId,
            name                = name,
            version             = version,
            description         = description,
            author              = author,
            apkPath             = apkPath,
            mainClass           = mainClass,
            requiredPermissions = perms,
            handledCommands     = cmds,
            minAladdinVersion   = minAladdinVersion,
            tags                = tagList,
            iconResName         = iconResName,
            state               = runCatching { PluginState.valueOf(state) }.getOrDefault(PluginState.ERROR),
            installedAtMs       = installedAtMs,
            lastLoadedAtMs      = lastLoadedAtMs,
            loadError           = loadError
        )
    }
}
