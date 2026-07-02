package com.aladdin.plugin.discovery

import android.content.Context
import android.util.Log
import com.aladdin.plugin.model.PluginInfo
import com.aladdin.plugin.model.PluginState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans the plugin directory for APK files and returns [PluginInfo] for each found plugin.
 * Default plugin directory: /sdcard/Android/data/<app>/files/plugins/
 */
class PluginDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "PluginDiscovery"
        const val PLUGIN_DIR_NAME = "plugins"
        const val PLUGIN_MANIFEST = "plugin.json"
    }

    /** Returns the canonical plugin directory, creating it if absent. */
    fun getPluginDirectory(): File {
        val dir = File(context.getExternalFilesDir(null), PLUGIN_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Scans [pluginDir] for *.apk files and parses their embedded plugin.json.
     * @return list of [PluginInfo] for all valid plugins found.
     */
    suspend fun scanPlugins(pluginDir: File = getPluginDirectory()): List<PluginInfo> =
        withContext(Dispatchers.IO) {
            if (!pluginDir.exists() || !pluginDir.isDirectory) {
                Log.w(TAG, "Plugin directory does not exist: ${pluginDir.absolutePath}")
                return@withContext emptyList()
            }

            val apkFiles = pluginDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
                ?: return@withContext emptyList()

            Log.i(TAG, "Found ${apkFiles.size} APK(s) in ${pluginDir.absolutePath}")

            apkFiles.mapNotNull { apk ->
                runCatching { ApkScanner(context).extractPluginInfo(apk) }
                    .onFailure { Log.e(TAG, "Failed to scan ${apk.name}", it) }
                    .getOrNull()
            }
        }

    /**
     * Watch plugin directory for file-system changes.
     * Simplified polling implementation — replace with FileObserver for production.
     */
    fun startWatching(onChanged: () -> Unit): PluginDirectoryWatcher {
        return PluginDirectoryWatcher(getPluginDirectory(), onChanged)
    }
}

class PluginDirectoryWatcher(
    private val dir: File,
    private val onChanged: () -> Unit
) {
    private var lastSnapshot: Set<String> = emptySet()
    private var running = false

    fun start() {
        running = true
        lastSnapshot = currentSnapshot()
        Thread {
            while (running) {
                Thread.sleep(3_000)
                val current = currentSnapshot()
                if (current != lastSnapshot) {
                    lastSnapshot = current
                    onChanged()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() { running = false }

    private fun currentSnapshot(): Set<String> =
        dir.listFiles()?.map { "${it.name}:${it.lastModified()}" }?.toSet() ?: emptySet()
}
