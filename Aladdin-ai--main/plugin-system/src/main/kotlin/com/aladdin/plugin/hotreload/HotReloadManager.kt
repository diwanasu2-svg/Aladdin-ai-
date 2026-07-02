package com.aladdin.plugin.hotreload

import android.util.Log
import com.aladdin.plugin.api.BasePlugin
import com.aladdin.plugin.discovery.PluginLoader
import com.aladdin.plugin.model.PluginInfo
import com.aladdin.plugin.model.PluginState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages hot-reload of plugins without restarting the host application.
 *
 * Hot-reload sequence:
 *  1. Call [unload] on the running plugin instance → onUnload() fires
 *  2. Remove old classloader reference (GC will collect it)
 *  3. Re-scan the APK (it may have been replaced on disk)
 *  4. Load fresh instance via [PluginLoader]
 *  5. Re-inject context and call onLoad()
 */
class HotReloadManager(private val loader: PluginLoader) {

    companion object {
        private const val TAG = "HotReloadManager"
    }

    /**
     * Unloads the currently running plugin and loads a fresh instance.
     *
     * @param info         Current [PluginInfo] (apkPath may point to updated APK)
     * @param current      Currently active plugin instance
     * @param contextInjector  Lambda that injects [PluginContext] into the new instance
     * @return newly loaded and initialised plugin instance
     */
    suspend fun hotReload(
        info: PluginInfo,
        current: BasePlugin,
        contextInjector: (BasePlugin) -> Unit
    ): HotReloadResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Hot-reload starting for '${info.pluginId}'")

        // Step 1: graceful unload
        runCatching { current.onUnload() }
            .onFailure { Log.w(TAG, "onUnload threw for '${info.pluginId}'", it) }

        // Step 2: release old loader's dex cache
        loader.unload(info.pluginId)

        // Step 3+4: load fresh instance
        val newInstance = runCatching { loader.load(info) }
            .onFailure {
                Log.e(TAG, "Hot-reload FAILED to load '${info.pluginId}'", it)
                return@withContext HotReloadResult.Failure(it.message ?: "Load error")
            }
            .getOrNull() ?: return@withContext HotReloadResult.Failure("Null instance")

        // Step 5: inject context and init
        contextInjector(newInstance)
        val loaded = runCatching { newInstance.onLoad() }.getOrDefault(false)
        if (!loaded) return@withContext HotReloadResult.Failure("onLoad() returned false")

        Log.i(TAG, "Hot-reload SUCCESS for '${info.pluginId}' v${info.version}")
        HotReloadResult.Success(newInstance)
    }

    sealed class HotReloadResult {
        data class Success(val plugin: BasePlugin) : HotReloadResult()
        data class Failure(val reason: String) : HotReloadResult()
    }
}
