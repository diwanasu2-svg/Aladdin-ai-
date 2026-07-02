package com.aladdin.plugin.api

import android.content.Context
import com.aladdin.plugin.config.PluginConfigManager
import com.aladdin.plugin.permissions.PluginPermissionManager

/**
 * Sandbox context injected into every plugin.
 * Plugins only access core services through this interface — never directly.
 */
class PluginContext(
    val pluginId: String,
    val androidContext: Context,
    val config: PluginConfigManager,
    val permissions: PluginPermissionManager,
    val coreServices: CoreServiceProvider,
    val logger: PluginLogger
) {
    /** Convenience: check a permission before calling a restricted service */
    fun requirePermission(permission: PluginPermissionType) {
        check(permissions.hasPermission(pluginId, permission)) {
            "Plugin '$pluginId' does not have permission: $permission"
        }
    }
}

/** Thin logging facade so plugins don't depend on Android Log directly */
interface PluginLogger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}
