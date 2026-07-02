package com.aladdin.assistant.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 5 – Agent Communication Bus
 *
 * Provides a shared message bus for all agents to exchange information.
 * Supports:
 *  - Standard message format with sender/receiver/type/payload
 *  - Task handoff between agents
 *  - Shared context store (key-value)
 *  - Communication log for debugging
 */
object AgentCommunication {

    private const val TAG = "AgentCommunication"

    // ── Message model ───────────────────────────────────────────────────────

    enum class MessageType {
        TASK_REQUEST,
        TASK_RESULT,
        TASK_HANDOFF,
        CONTEXT_UPDATE,
        MEMORY_SYNC,
        STATUS_UPDATE,
        ERROR,
        EMERGENCY
    }

    data class AgentMessage(
        val id: String = UUID.randomUUID().toString(),
        val sender: AgentType,
        val receiver: AgentType,          // AgentType.ALL = broadcast
        val type: MessageType,
        val taskId: String,
        val payload: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis(),
        val priority: Int = 5            // 1 (highest) … 10 (lowest)
    )

    enum class AgentType {
        COORDINATOR, PLANNER, RESEARCH, BROWSER, CODING,
        MEMORY, SAFETY, VISION, ORCHESTRATOR, ALL
    }

    // ── Shared bus ──────────────────────────────────────────────────────────

    private val _messageBus = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 256)
    val messageBus: SharedFlow<AgentMessage> = _messageBus.asSharedFlow()

    // ── Shared context ──────────────────────────────────────────────────────

    private val sharedContext = ConcurrentHashMap<String, Any>()

    // ── Communication log ───────────────────────────────────────────────────

    private val communicationLog = CopyOnWriteArrayList<AgentMessage>()
    private const val MAX_LOG_SIZE = 500

    // ── Public API ──────────────────────────────────────────────────────────

    /** Send a message on the bus. Every subscribed agent will receive it. */
    suspend fun send(message: AgentMessage) {
        logMessage(message)
        _messageBus.emit(message)
        Log.d(TAG, "MSG [${message.sender}→${message.receiver}] type=${message.type} task=${message.taskId}")
    }

    /** Convenience builder for a task-request message. */
    suspend fun requestTask(
        sender: AgentType,
        receiver: AgentType,
        taskId: String,
        payload: Map<String, Any>,
        priority: Int = 5
    ) = send(
        AgentMessage(
            sender = sender,
            receiver = receiver,
            type = MessageType.TASK_REQUEST,
            taskId = taskId,
            payload = payload,
            priority = priority
        )
    )

    /** Convenience builder for a task-result message. */
    suspend fun reportResult(
        sender: AgentType,
        receiver: AgentType,
        taskId: String,
        result: Any,
        success: Boolean = true
    ) = send(
        AgentMessage(
            sender = sender,
            receiver = receiver,
            type = MessageType.TASK_RESULT,
            taskId = taskId,
            payload = mapOf("result" to result, "success" to success)
        )
    )

    /** Hand a task off to another agent, carrying forward the existing payload. */
    suspend fun handOff(
        from: AgentType,
        to: AgentType,
        taskId: String,
        payload: Map<String, Any>
    ) = send(
        AgentMessage(
            sender = from,
            receiver = to,
            type = MessageType.TASK_HANDOFF,
            taskId = taskId,
            payload = payload,
            priority = 3
        )
    )

    /** Broadcast a context update so all agents can sync. */
    suspend fun broadcastContext(sender: AgentType, taskId: String, key: String, value: Any) {
        setContext(key, value)
        send(
            AgentMessage(
                sender = sender,
                receiver = AgentType.ALL,
                type = MessageType.CONTEXT_UPDATE,
                taskId = taskId,
                payload = mapOf("key" to key, "value" to value)
            )
        )
    }

    // ── Shared context store ────────────────────────────────────────────────

    fun setContext(key: String, value: Any) {
        sharedContext[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getContext(key: String): T? = sharedContext[key] as? T

    fun clearContext(key: String) { sharedContext.remove(key) }

    fun clearAllContext() { sharedContext.clear() }

    // ── Log ─────────────────────────────────────────────────────────────────

    private fun logMessage(msg: AgentMessage) {
        if (communicationLog.size >= MAX_LOG_SIZE) {
            communicationLog.removeAt(0)
        }
        communicationLog.add(msg)
    }

    fun getLog(): List<AgentMessage> = communicationLog.toList()

    fun getLogForTask(taskId: String): List<AgentMessage> =
        communicationLog.filter { it.taskId == taskId }

    fun clearLog() { communicationLog.clear() }
}
