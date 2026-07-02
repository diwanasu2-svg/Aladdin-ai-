package com.aladdin.plugin.api

/**
 * A command dispatched to a plugin via [BasePlugin.onCommand].
 */
data class PluginCommand(
    /** The command name, e.g. "weather.get" */
    val name: String,
    /** Arbitrary key-value arguments */
    val args: Map<String, Any?> = emptyMap(),
    /** Optional raw natural-language input that triggered this command */
    val rawInput: String? = null
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> arg(key: String): T? = args[key] as? T
    fun <T> argOrDefault(key: String, default: T): T = arg(key) ?: default
}

/**
 * Result returned from [BasePlugin.onCommand].
 */
sealed class PluginCommandResult {
    data class Success(
        val message: String,
        val data: Map<String, Any?> = emptyMap(),
        val speakAloud: Boolean = true
    ) : PluginCommandResult()

    data class Failure(
        val reason: String,
        val exception: Throwable? = null
    ) : PluginCommandResult()

    data class Deferred(
        val trackingId: String,
        val estimatedMs: Long = -1
    ) : PluginCommandResult()

    object NotHandled : PluginCommandResult()
}

/**
 * Permission types plugins can request.
 * Maps to Android permissions and Aladdin internal capabilities.
 */
enum class PluginPermissionType(val label: String, val description: String) {
    INTERNET("Internet", "Access the internet"),
    MICROPHONE("Microphone", "Record audio via microphone"),
    CAMERA("Camera", "Access camera"),
    CONTACTS("Contacts", "Read/write contacts"),
    CALENDAR("Calendar", "Read/write calendar events"),
    STORAGE("Storage", "Read/write external storage"),
    LOCATION("Location", "Access device location"),
    NOTIFICATIONS("Notifications", "Send notifications"),
    CORE_LLM("Core LLM", "Send prompts to the AI engine"),
    CORE_MEMORY("Core Memory", "Read/write smart memory"),
    CORE_VOICE("Core Voice", "Synthesise speech / listen"),
    CORE_TOOLS("Core Tools", "Register and call tools"),
    SYSTEM_SETTINGS("System Settings", "Modify system settings"),
    ACCESSIBILITY("Accessibility", "Access accessibility APIs")
}
