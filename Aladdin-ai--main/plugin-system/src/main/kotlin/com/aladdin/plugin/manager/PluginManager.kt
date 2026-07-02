package com.aladdin.plugin.manager

import android.content.Context
import android.util.Log
import com.aladdin.plugin.api.*
import com.aladdin.plugin.config.PluginConfigManager
import com.aladdin.plugin.discovery.PluginDiscovery
import com.aladdin.plugin.discovery.PluginLoader
import com.aladdin.plugin.docs.PluginDocGenerator
import com.aladdin.plugin.hotreload.HotReloadManager
import com.aladdin.plugin.model.PluginInfo
import com.aladdin.plugin.model.PluginState
import com.aladdin.plugin.permissions.PluginPermissionManager
import com.aladdin.plugin.registry.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central orchestrator for the Aladdin plugin system.
 *
 * Responsibilities:
 *  - Discover plugins on disk via [PluginDiscovery]
 *  - Load / unload plugin APKs via [PluginLoader]
 *  - Manage runtime plugin lifecycle
 *  - Route commands to the correct plugin
 *  - Coordinate permissions, config, hot-reload, and documentation
 */
class PluginManager(
    private val context: Context,
    private val coreServices: CoreServiceProvider
) {
    companion object {
        private const val TAG = "PluginManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val registry = PluginRegistry(context)
    private val discovery = PluginDiscovery(context)
    private val loader = PluginLoader(context)
    private val permissionManager = PluginPermissionManager(context)
    private val hotReloadManager = HotReloadManager(loader)

    /** Active plugin instances keyed by pluginId */
    private val activePlugins = mutableMapOf<String, BasePlugin>()

    /** File-system watcher for auto-discovery */
    private val watcher = discovery.startWatching { scope.launch { discoverAndRegister() } }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        watcher.start()
        scope.launch { discoverAndRegister() }
        Log.i(TAG, "PluginManager started")
    }

    fun stop() {
        watcher.stop()
        activePlugins.values.forEach { runCatching { it.onUnload() } }
        activePlugins.clear()
        Log.i(TAG, "PluginManager stopped")
    }

    // ─── Discovery & Registration ─────────────────────────────────────────────

    suspend fun discoverAndRegister() {
        val found = discovery.scanPlugins()
        Log.i(TAG, "Discovered ${found.size} plugin(s)")
        found.forEach { info ->
            val existing = registry.get(info.pluginId)
            if (existing == null || existing.version != info.version) {
                registry.register(info)
                Log.i(TAG, "Registered plugin: ${info.pluginId} v${info.version}")
            }
        }
    }

    // ─── Load / Unload ────────────────────────────────────────────────────────

    suspend fun loadPlugin(pluginId: String): LoadResult {
        val info = registry.get(pluginId)
            ?: return LoadResult.Failure("Plugin not found in registry: $pluginId")

        if (info.state == PluginState.ACTIVE)
            return LoadResult.AlreadyLoaded

        val pending = permissionManager.pendingConsent(pluginId, info.requiredPermissions)
        if (pending.isNotEmpty())
            return LoadResult.NeedsConsent(pending)

        return withContext(Dispatchers.IO) {
            registry.updateState(pluginId, PluginState.LOADING)
            runCatching {
                val plugin = loader.load(info)
                injectContext(plugin, info)
                val ok = plugin.onLoad()
                if (!ok) {
                    registry.updateState(pluginId, PluginState.ERROR, "onLoad() returned false")
                    return@runCatching LoadResult.Failure("onLoad() returned false")
                }
                activePlugins[pluginId] = plugin
                registry.updateState(pluginId, PluginState.ACTIVE)
                Log.i(TAG, "Loaded plugin '${info.name}' v${info.version}")
                LoadResult.Success(plugin)
            }.getOrElse { e ->
                registry.updateState(pluginId, PluginState.ERROR, e.message)
                Log.e(TAG, "Failed to load '$pluginId'", e)
                LoadResult.Failure(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun unloadPlugin(pluginId: String) {
        activePlugins.remove(pluginId)?.let { plugin ->
            runCatching { plugin.onUnload() }
                .onFailure { Log.w(TAG, "onUnload threw for '$pluginId'", it) }
            loader.unload(pluginId)
            registry.updateState(pluginId, PluginState.DISCOVERED)
            Log.i(TAG, "Unloaded plugin '$pluginId'")
        }
    }

    suspend fun hotReload(pluginId: String): Boolean {
        val current = activePlugins[pluginId] ?: run {
            Log.w(TAG, "Hot-reload requested but '$pluginId' is not active")
            return false
        }
        val info = registry.get(pluginId) ?: return false
        val result = hotReloadManager.hotReload(info, current) { plugin -> injectContext(plugin, info) }
        return when (result) {
            is HotReloadManager.HotReloadResult.Success -> {
                activePlugins[pluginId] = result.plugin
                registry.updateState(pluginId, PluginState.ACTIVE)
                true
            }
            is HotReloadManager.HotReloadResult.Failure -> {
                registry.updateState(pluginId, PluginState.ERROR, result.reason)
                false
            }
        }
    }

    // ─── Command Routing ──────────────────────────────────────────────────────

    suspend fun dispatch(command: PluginCommand): PluginCommandResult {
        val handler = activePlugins.values.firstOrNull { p ->
            p.handledCommands.any { it == command.name || it == "*" }
        }
        if (handler == null) {
            Log.w(TAG, "No plugin handles command '${command.name}'")
            return PluginCommandResult.NotHandled
        }
        return withContext(Dispatchers.IO) {
            runCatching { handler.onCommand(command) }
                .getOrElse { e -> PluginCommandResult.Failure("Plugin threw: ${e.message}", e) }
        }
    }

    // ─── Enable / Disable ────────────────────────────────────────────────────

    suspend fun enablePlugin(pluginId: String) {
        registry.setEnabled(pluginId, true)
        loadPlugin(pluginId)
    }

    suspend fun disablePlugin(pluginId: String) {
        unloadPlugin(pluginId)
        registry.setEnabled(pluginId, false)
        registry.updateState(pluginId, PluginState.DISABLED)
    }

    // ─── Docs ─────────────────────────────────────────────────────────────────

    suspend fun generateDocs(outputDir: File) {
        val all = registry.allPlugins
        // Collect current snapshot
        val plugins = mutableListOf<PluginInfo>()
        registry.allPlugins.collect { list -> plugins.addAll(list); return@collect }
        PluginDocGenerator.writeToDirectory(plugins, outputDir)
        Log.i(TAG, "Docs written to ${outputDir.absolutePath}")
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    val allPlugins: Flow<List<PluginInfo>> get() = registry.allPlugins
    fun getActive(): List<BasePlugin> = activePlugins.values.toList()
    fun getPermissionManager(): PluginPermissionManager = permissionManager

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun injectContext(plugin: BasePlugin, info: PluginInfo) {
        val config = PluginConfigManager(context, info.pluginId)
        val logger = AndroidPluginLogger(info.pluginId)
        plugin.pluginContext = PluginContext(
            pluginId     = info.pluginId,
            androidContext = context,
            config       = config,
            permissions  = permissionManager,
            coreServices = RestrictedCoreServiceProvider(coreServices, permissionManager, info.pluginId),
            logger       = logger
        )
    }
}

// ─── Result Types ─────────────────────────────────────────────────────────────

sealed class LoadResult {
    data class Success(val plugin: BasePlugin) : LoadResult()
    data class Failure(val reason: String) : LoadResult()
    data class NeedsConsent(val permissions: List<com.aladdin.plugin.api.PluginPermissionType>) : LoadResult()
    object AlreadyLoaded : LoadResult()
}
