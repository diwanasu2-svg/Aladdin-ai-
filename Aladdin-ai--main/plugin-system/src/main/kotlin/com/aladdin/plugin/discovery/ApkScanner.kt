package com.aladdin.plugin.discovery

import android.content.Context
import android.content.pm.PackageManager
import com.aladdin.plugin.api.PluginPermissionType
import com.aladdin.plugin.model.PluginInfo
import com.aladdin.plugin.model.PluginState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads an APK file and extracts [PluginInfo] from its embedded plugin.json asset.
 */
class ApkScanner(private val context: Context) {

    companion object {
        private const val MANIFEST_ASSET = "assets/plugin.json"
    }

    fun extractPluginInfo(apkFile: File): PluginInfo {
        val json = readPluginManifest(apkFile)
        return parsePluginInfo(json, apkFile.absolutePath)
    }

    private fun readPluginManifest(apkFile: File): JSONObject {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_ASSET)
                ?: throw IllegalStateException("No $MANIFEST_ASSET in ${apkFile.name}")
            val content = zip.getInputStream(entry).bufferedReader().readText()
            return JSONObject(content)
        }
    }

    private fun parsePluginInfo(json: JSONObject, apkPath: String): PluginInfo {
        val permissionsArray: JSONArray = json.optJSONArray("requiredPermissions") ?: JSONArray()
        val permissions = (0 until permissionsArray.length())
            .mapNotNull { i ->
                runCatching {
                    PluginPermissionType.valueOf(permissionsArray.getString(i))
                }.getOrNull()
            }

        val commandsArray: JSONArray = json.optJSONArray("handledCommands") ?: JSONArray()
        val commands = (0 until commandsArray.length()).map { i -> commandsArray.getString(i) }

        val tagsArray: JSONArray = json.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArray.length()).map { i -> tagsArray.getString(i) }

        return PluginInfo(
            pluginId         = json.getString("pluginId"),
            name             = json.getString("name"),
            version          = json.getString("version"),
            description      = json.optString("description", ""),
            author           = json.optString("author", "Unknown"),
            apkPath          = apkPath,
            mainClass        = json.getString("mainClass"),
            requiredPermissions = permissions,
            handledCommands  = commands,
            minAladdinVersion = json.optString("minAladdinVersion", "1.0.0"),
            tags             = tags,
            iconResName      = json.optString("iconResName", null).takeIf { it?.isNotBlank() == true },
            state            = PluginState.DISCOVERED
        )
    }
}
