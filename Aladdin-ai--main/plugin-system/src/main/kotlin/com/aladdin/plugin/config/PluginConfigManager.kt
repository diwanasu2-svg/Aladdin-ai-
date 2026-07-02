package com.aladdin.plugin.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Per-plugin configuration manager.
 * Values are stored in SharedPreferences backed by an optional JSON defaults file.
 *
 * Priority: runtime override > SharedPreferences > plugin defaults (plugin.json "config" block)
 */
class PluginConfigManager(
    private val context: Context,
    private val pluginId: String
) {
    companion object {
        private const val TAG = "PluginConfigManager"
        private const val PREFS_PREFIX = "aladdin_plugin_cfg_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "$PREFS_PREFIX${pluginId.replace('.', '_')}", Context.MODE_PRIVATE
    )

    private var defaults: Map<String, Any> = emptyMap()

    fun loadDefaults(defaultsJson: String) {
        defaults = parseJson(defaultsJson)
        Log.d(TAG, "[$pluginId] Loaded ${defaults.size} default config keys")
    }

    fun loadDefaultsFromFile(file: File) {
        if (file.exists()) loadDefaults(file.readText())
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    fun getString(key: String, fallback: String = ""): String =
        prefs.getString(key, null) ?: (defaults[key] as? String) ?: fallback

    fun getInt(key: String, fallback: Int = 0): Int =
        if (prefs.contains(key)) prefs.getInt(key, fallback)
        else (defaults[key] as? Int) ?: fallback

    fun getLong(key: String, fallback: Long = 0L): Long =
        if (prefs.contains(key)) prefs.getLong(key, fallback)
        else (defaults[key] as? Long) ?: fallback

    fun getFloat(key: String, fallback: Float = 0f): Float =
        if (prefs.contains(key)) prefs.getFloat(key, fallback)
        else (defaults[key] as? Float) ?: fallback

    fun getBoolean(key: String, fallback: Boolean = false): Boolean =
        if (prefs.contains(key)) prefs.getBoolean(key, fallback)
        else (defaults[key] as? Boolean) ?: fallback

    fun getAll(): Map<String, Any?> = defaults + prefs.all

    // ─── Write (runtime changes) ──────────────────────────────────────────────

    fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun set(key: String, value: Int)    = prefs.edit().putInt(key, value).apply()
    fun set(key: String, value: Long)   = prefs.edit().putLong(key, value).apply()
    fun set(key: String, value: Float)  = prefs.edit().putFloat(key, value).apply()
    fun set(key: String, value: Boolean)= prefs.edit().putBoolean(key, value).apply()

    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.i(TAG, "[$pluginId] Config reset to defaults")
    }

    // ─── JSON helper ──────────────────────────────────────────────────────────

    private fun parseJson(json: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        runCatching {
            val obj = JSONObject(json)
            val config = obj.optJSONObject("config") ?: obj
            config.keys().forEach { key ->
                result[key] = config.get(key)
            }
        }.onFailure { Log.w(TAG, "Failed to parse config JSON", it) }
        return result
    }
}
