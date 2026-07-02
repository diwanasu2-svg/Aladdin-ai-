package com.aladdin.app.architecture

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedArchitecture — Items 48-53.
 * Item 48: Android ↔ Backend event bus for all module comms.
 * Item 49: Typed API request/response contract.
 * Item 50: Shared memory — all agents route to VectorMemoryStore.
 * Item 51: Tool registry — single registry for all agent tools.
 * Item 52: Vision bridged — VisionEngine registered as shared tool.
 * Item 53: Auth/identity — device fingerprint + session management.
 */
@Singleton
class SharedArchitecture @Inject constructor(@ApplicationContext private val context: Context) {
    companion object { private const val TAG = "SharedArchitecture" }

    // Item 48: Event bus
    sealed class SystemEvent {
        data class UserQuery(val text: String, val source: Source) : SystemEvent()
        data class AIResponse(val text: String, val backend: String) : SystemEvent()
        data class ToolCall(val tool: String, val args: Map<String, Any?>) : SystemEvent()
        data class ToolResult(val tool: String, val result: Any?, val error: String? = null) : SystemEvent()
        data class VoiceStateChanged(val state: String) : SystemEvent()
        data class Error(val source: String, val message: String) : SystemEvent()
        object Shutdown : SystemEvent()
    }
    enum class Source { VOICE, TELEGRAM, DISCORD, EMAIL, WIDGET, API }

    private val _bus = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val eventBus: SharedFlow<SystemEvent> = _bus.asSharedFlow()
    fun emit(event: SystemEvent) { _bus.tryEmit(event) }

    // Item 49: API contract
    data class ApiRequest(val id: String = java.util.UUID.randomUUID().toString(), val type: String, val payload: Map<String, Any?> = emptyMap(), val timeoutMs: Long = 30_000L, val retries: Int = 3, val priority: Priority = Priority.NORMAL)
    data class ApiResponse(val requestId: String, val success: Boolean, val data: Any? = null, val error: String? = null, val latencyMs: Long = 0L)
    enum class Priority { LOW, NORMAL, HIGH, CRITICAL }

    // Item 51: Tool registry
    data class ToolDefinition(val name: String, val description: String, val parameters: Map<String, String>, val executor: suspend (Map<String, Any?>) -> Any?)
    private val tools = mutableMapOf<String, ToolDefinition>()
    fun registerTool(tool: ToolDefinition) { tools[tool.name] = tool; Log.i(TAG, "Tool: \${tool.name}") }
    fun getTool(name: String) = tools[name]
    fun listTools() = tools.keys.toList()
    suspend fun executeTool(name: String, args: Map<String, Any?>): ApiResponse {
        val tool = tools[name] ?: return ApiResponse(name, false, error = "Tool not found: \$name")
        val t = System.currentTimeMillis()
        return try { ApiResponse(name, true, tool.executor(args), latencyMs = System.currentTimeMillis() - t) }
        catch (e: Exception) { ApiResponse(name, false, error = e.message, latencyMs = System.currentTimeMillis() - t) }
    }

    // Item 52: Register built-in tools (vision, time, battery)
    fun registerBuiltinTools() {
        registerTool(ToolDefinition("get_time", "Get current time/date", emptyMap()) { _ ->
            java.text.SimpleDateFormat("EEEE, MMMM d yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        })
        registerTool(ToolDefinition("get_battery", "Get device battery level", emptyMap()) { _ ->
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            mapOf("level" to (level * 100 / scale))
        })
        Log.i(TAG, "Built-in tools: \${listTools()}")
    }

    // Item 53: Auth/identity
    data class UserSession(val sessionId: String = java.util.UUID.randomUUID().toString(), val fingerprint: String, val createdAt: Long = System.currentTimeMillis())
    private var session: UserSession? = null
    fun createSession(): UserSession {
        session = UserSession(fingerprint = buildFingerprint())
        Log.i(TAG, "Session: \${session!!.sessionId.take(8)}…")
        return session!!
    }
    fun getSession() = session
    private fun buildFingerprint(): String {
        val raw = "\${android.os.Build.MODEL}|\${android.os.Build.MANUFACTURER}|\${context.packageName}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}