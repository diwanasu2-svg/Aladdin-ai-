package com.aladdin.plugin.api

/**
 * Restricted gateway to Aladdin core services.
 * Plugins call these only after their required permissions are granted.
 */
interface CoreServiceProvider {
    /** Send a prompt to the LLM and get a response (requires CORE_LLM) */
    suspend fun queryLLM(prompt: String, systemPrompt: String? = null): String

    /** Store a memory fact (requires CORE_MEMORY) */
    suspend fun storeMemory(key: String, value: String, importance: Float = 0.5f)

    /** Retrieve memory facts by semantic query (requires CORE_MEMORY) */
    suspend fun queryMemory(query: String, limit: Int = 5): List<MemoryFact>

    /** Speak text aloud via TTS (requires CORE_VOICE) */
    suspend fun speak(text: String)

    /** Register a new tool with Aladdin's tool system (requires CORE_TOOLS) */
    fun registerTool(toolName: String, description: String, handler: suspend (Map<String, Any?>) -> String)

    /** Unregister a previously registered tool */
    fun unregisterTool(toolName: String)

    /** Emit a notification (requires NOTIFICATIONS) */
    fun sendNotification(title: String, body: String, channelId: String = "plugins")
}

data class MemoryFact(
    val key: String,
    val value: String,
    val importance: Float,
    val timestampMs: Long
)
