package com.aladdin.plugin.discovery

import android.content.Context
import android.util.Log
import com.aladdin.plugin.api.BasePlugin
import com.aladdin.plugin.model.PluginInfo
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Loads a [BasePlugin] implementation from a plugin APK using [DexClassLoader].
 */
class PluginLoader(private val context: Context) {

    companion object {
        private const val TAG = "PluginLoader"
    }

    // Separate optimised dex output dir per plugin to avoid collisions
    private fun dexOutputDir(pluginId: String): File {
        val dir = File(context.codeCacheDir, "plugin-dex/$pluginId")
        dir.mkdirs()
        return dir
    }

    /**
     * Dynamically loads the plugin's main class from its APK and instantiates it.
     * The APK is added to a new [DexClassLoader] isolated from the host app.
     *
     * @param info [PluginInfo] describing the plugin to load.
     * @return instantiated [BasePlugin] (not yet initialised).
     * @throws PluginLoadException on any loading failure.
     */
    fun load(info: PluginInfo): BasePlugin {
        val apkFile = File(info.apkPath)
        require(apkFile.exists()) { "APK not found: ${info.apkPath}" }

        val dexDir = dexOutputDir(info.pluginId)

        Log.i(TAG, "Loading plugin '${info.pluginId}' from ${apkFile.name}")

        val classLoader = DexClassLoader(
            apkFile.absolutePath,
            dexDir.absolutePath,
            null,                          // native libs (null = none)
            context.classLoader            // parent — host app's classloader
        )

        return try {
            val clazz = classLoader.loadClass(info.mainClass)
            val instance = clazz.getDeclaredConstructor().newInstance()
            instance as? BasePlugin
                ?: throw PluginLoadException(info.pluginId, "Main class does not extend BasePlugin")
        } catch (e: ClassNotFoundException) {
            throw PluginLoadException(info.pluginId, "Main class '${info.mainClass}' not found in APK", e)
        } catch (e: ClassCastException) {
            throw PluginLoadException(info.pluginId, "Main class does not extend BasePlugin", e)
        } catch (e: Exception) {
            throw PluginLoadException(info.pluginId, "Failed to instantiate plugin: ${e.message}", e)
        }
    }

    /**
     * Attempts to unload a plugin by clearing its dex cache directory.
     * The actual class GC happens when there are no more references to the classloader.
     */
    fun unload(pluginId: String) {
        dexOutputDir(pluginId).deleteRecursively()
        Log.i(TAG, "Unloaded dex cache for plugin '$pluginId'")
    }
}

class PluginLoadException(
    pluginId: String,
    message: String,
    cause: Throwable? = null
) : Exception("[$pluginId] $message", cause)
