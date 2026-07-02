package com.aladdin.plugin.api

import android.content.Context

/**
 * Abstract base class for all Aladdin plugins.
 * Every plugin APK must contain exactly one class that extends BasePlugin.
 */
abstract class BasePlugin {

    lateinit var pluginContext: PluginContext
        internal set

    /** Unique reverse-domain identifier, e.g. "com.example.weather" */
    abstract val pluginId: String

    /** Human-readable name shown in the UI */
    abstract val name: String

    /** SemVer string, e.g. "1.2.0" */
    abstract val version: String

    /** Short description shown in plugin manager UI */
    abstract val description: String

    /** Author / publisher name */
    abstract val author: String

    /** Permissions this plugin requires; must match plugin.json declaration */
    abstract val requiredPermissions: List<PluginPermissionType>

    /** Commands this plugin handles */
    abstract val handledCommands: List<String>

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Called once after the plugin is loaded and its context is injected.
     * Initialise resources here. Return false to abort loading.
     */
    abstract fun onLoad(): Boolean

    /**
     * Called before the plugin is unloaded (hot-reload or disable).
     * Release all resources, cancel coroutines, close files.
     */
    abstract fun onUnload()

    /**
     * Called when a command matching [handledCommands] is dispatched.
     * @return [PluginCommandResult] indicating success/failure/data
     */
    abstract fun onCommand(command: PluginCommand): PluginCommandResult

    /**
     * Called when the plugin's configuration changes at runtime.
     * The plugin should re-read values from [pluginContext.config].
     */
    open fun onConfigChanged() {}

    /**
     * Optional: called on each Aladdin conversation turn so the plugin can
     * inject context / suggestions.
     */
    open fun onConversationTurn(userInput: String): String? = null

    // ─── Helpers ──────────────────────────────────────────────────────────────

    protected val androidContext: Context get() = pluginContext.androidContext
    protected val logger get() = pluginContext.logger
    protected val config get() = pluginContext.config
}
