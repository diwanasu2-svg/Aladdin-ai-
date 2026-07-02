package com.aladdin.plugin.sample

import com.aladdin.plugin.api.*
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sample Aladdin plugin demonstrating all plugin system features:
 *  - Command handling
 *  - Config access
 *  - Core service access (LLM, memory, voice, tools)
 *  - Conversation hook
 *  - Proper onLoad/onUnload lifecycle
 */
class SamplePlugin : BasePlugin() {

    override val pluginId    = "com.aladdin.plugin.sample"
    override val name        = "Sample Plugin"
    override val version     = "1.0.0"
    override val description = "Demonstrates the Aladdin plugin API. Shows time, echoes messages, and queries the AI."
    override val author      = "Aladdin Team"

    override val requiredPermissions = listOf(
        PluginPermissionType.CORE_LLM,
        PluginPermissionType.CORE_MEMORY,
        PluginPermissionType.CORE_VOICE,
        PluginPermissionType.CORE_TOOLS,
        PluginPermissionType.NOTIFICATIONS
    )

    override val handledCommands = listOf(
        "sample.hello",
        "sample.time",
        "sample.echo",
        "sample.ask",
        "sample.remember",
        "sample.recall",
        "sample.notify"
    )

    private var greeting = "Hello"

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onLoad(): Boolean {
        greeting = config.getString("greeting", "Hello")
        logger.i(TAG, "SamplePlugin loaded. Greeting: '$greeting'")

        // Register a tool so Aladdin's AI can call it naturally
        pluginContext.requirePermission(PluginPermissionType.CORE_TOOLS)
        pluginContext.coreServices.registerTool(
            toolName    = "sample_time",
            description = "Returns the current date and time."
        ) { _ -> getCurrentTime() }

        return true
    }

    override fun onUnload() {
        runCatching {
            pluginContext.coreServices.unregisterTool("sample_time")
        }
        logger.i(TAG, "SamplePlugin unloaded.")
    }

    override fun onConfigChanged() {
        greeting = config.getString("greeting", "Hello")
        logger.i(TAG, "Config updated. New greeting: '$greeting'")
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    override fun onCommand(command: PluginCommand): PluginCommandResult = when (command.name) {

        "sample.hello" -> {
            val who = command.argOrDefault("name", "World")
            PluginCommandResult.Success("$greeting, $who! I am the Sample Plugin.")
        }

        "sample.time" -> {
            PluginCommandResult.Success("The current time is: ${getCurrentTime()}")
        }

        "sample.echo" -> {
            val text = command.argOrDefault("text", "(nothing to echo)")
            PluginCommandResult.Success("Echo: $text")
        }

        "sample.ask" -> {
            val question = command.arg<String>("question")
                ?: return PluginCommandResult.Failure("Missing 'question' argument")
            pluginContext.requirePermission(PluginPermissionType.CORE_LLM)
            val answer = runBlocking { pluginContext.coreServices.queryLLM(question) }
            PluginCommandResult.Success(answer)
        }

        "sample.remember" -> {
            val key   = command.arg<String>("key")   ?: return PluginCommandResult.Failure("Missing 'key'")
            val value = command.arg<String>("value") ?: return PluginCommandResult.Failure("Missing 'value'")
            pluginContext.requirePermission(PluginPermissionType.CORE_MEMORY)
            runBlocking { pluginContext.coreServices.storeMemory(key, value, 0.8f) }
            PluginCommandResult.Success("Remembered: $key = $value")
        }

        "sample.recall" -> {
            val query = command.argOrDefault("query", "")
            pluginContext.requirePermission(PluginPermissionType.CORE_MEMORY)
            val facts = runBlocking { pluginContext.coreServices.queryMemory(query, limit = 3) }
            if (facts.isEmpty()) {
                PluginCommandResult.Success("No memories found for '$query'.")
            } else {
                val summary = facts.joinToString("\n") { "• ${it.key}: ${it.value}" }
                PluginCommandResult.Success("Found ${facts.size} memory/memories:\n$summary")
            }
        }

        "sample.notify" -> {
            val title = command.argOrDefault("title", "Sample Plugin")
            val body  = command.argOrDefault("body",  "Hello from Sample Plugin!")
            pluginContext.requirePermission(PluginPermissionType.NOTIFICATIONS)
            pluginContext.coreServices.sendNotification(title, body)
            PluginCommandResult.Success("Notification sent.")
        }

        else -> PluginCommandResult.NotHandled
    }

    // ─── Conversation Hook ────────────────────────────────────────────────────

    override fun onConversationTurn(userInput: String): String? {
        return if (userInput.contains("sample plugin", ignoreCase = true)) {
            "I'm the Sample Plugin! Try commands: sample.hello, sample.time, sample.echo, sample.ask."
        } else null
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getCurrentTime(): String {
        val fmt = config.getString("timeFormat", "yyyy-MM-dd HH:mm:ss")
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
    }

    companion object {
        private const val TAG = "SamplePlugin"
    }
}
