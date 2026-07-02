package com.aladdin.assistant.agent

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 5 – Agent Priority System
 *
 * Manages execution order across all agents.
 *
 * Priority levels (lower number = higher priority):
 *   1 – CRITICAL (Safety, Emergency)
 *   2 – HIGH     (Planner, Memory)
 *   3 – MEDIUM   (Research, Browser, Coding)
 *   4 – LOW      (Background analytics, logging)
 *
 * Supports:
 *  - Priority queue with preemption
 *  - Parallel vs sequential execution decision
 *  - Resource conflict detection
 *  - Background task management
 *  - Emergency interrupt
 */
class AgentPrioritySystem {

    companion object {
        private const val TAG = "AgentPrioritySystem"

        /** Static priority table — lower number wins */
        val AGENT_PRIORITIES = mapOf(
            AgentCommunication.AgentType.SAFETY      to 1,
            AgentCommunication.AgentType.PLANNER     to 2,
            AgentCommunication.AgentType.MEMORY      to 2,
            AgentCommunication.AgentType.COORDINATOR to 2,
            AgentCommunication.AgentType.RESEARCH    to 3,
            AgentCommunication.AgentType.BROWSER     to 3,
            AgentCommunication.AgentType.CODING      to 3,
            AgentCommunication.AgentType.VISION      to 3,
            AgentCommunication.AgentType.ORCHESTRATOR to 2
        )

        /** Maximum concurrently running medium-priority tasks */
        private const val MAX_PARALLEL_MEDIUM = 3
    }

    // ── Task model ──────────────────────────────────────────────────────────

    data class PrioritizedTask(
        val taskId: String,
        val agentType: AgentCommunication.AgentType,
        val description: String,
        val action: suspend () -> Any,
        val onResult: (suspend (Any) -> Unit)? = null,
        val onError: (suspend (Throwable) -> Unit)? = null,
        val isBackground: Boolean = false,
        val canRunParallel: Boolean = false,
        val priority: Int = AGENT_PRIORITIES[AgentCommunication.AgentType.COORDINATOR] ?: 5,
        val submittedAt: Long = System.currentTimeMillis()
    ) : Comparable<PrioritizedTask> {
        override fun compareTo(other: PrioritizedTask): Int {
            val p = priority.compareTo(other.priority)
            return if (p != 0) p else submittedAt.compareTo(other.submittedAt)
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    private val queue = PriorityBlockingQueue<PrioritizedTask>()
    private val runningCount = AtomicInteger(0)
    private val emergencyFlag = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeJobs = mutableMapOf<String, Job>()
    private val resourceLocks = mutableSetOf<AgentCommunication.AgentType>()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        scope.launch { processingLoop() }
        Log.d(TAG, "Priority system started")
    }

    fun stop() {
        scope.cancel()
        Log.d(TAG, "Priority system stopped")
    }

    // ── Submission API ──────────────────────────────────────────────────────

    /** Submit a task for prioritised execution. */
    fun submit(
        taskId: String,
        agentType: AgentCommunication.AgentType,
        description: String,
        canRunParallel: Boolean = false,
        isBackground: Boolean = false,
        action: suspend () -> Any,
        onResult: (suspend (Any) -> Unit)? = null,
        onError: (suspend (Throwable) -> Unit)? = null
    ) {
        val p = AGENT_PRIORITIES[agentType] ?: 5
        val task = PrioritizedTask(
            taskId = taskId,
            agentType = agentType,
            description = description,
            action = action,
            onResult = onResult,
            onError = onError,
            isBackground = isBackground,
            canRunParallel = canRunParallel,
            priority = p
        )
        queue.offer(task)
        Log.d(TAG, "Submitted [$agentType] p=$p '$description'")
    }

    /** Trigger an emergency interrupt — drains medium/low tasks and runs the action immediately. */
    fun emergency(
        taskId: String,
        agentType: AgentCommunication.AgentType,
        description: String,
        action: suspend () -> Any,
        onResult: (suspend (Any) -> Unit)? = null
    ) {
        emergencyFlag.set(true)
        val task = PrioritizedTask(
            taskId = taskId,
            agentType = agentType,
            description = description,
            action = action,
            onResult = onResult,
            priority = 0           // beats everything
        )
        queue.offer(task)
        Log.w(TAG, "EMERGENCY submitted [$agentType] '$description'")
    }

    /** Cancel a queued or running task by ID. */
    fun cancel(taskId: String) {
        queue.removeIf { it.taskId == taskId }
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        Log.d(TAG, "Cancelled task $taskId")
    }

    // ── Core processing loop ────────────────────────────────────────────────

    private suspend fun processingLoop() {
        while (scope.isActive) {
            val task = withContext(Dispatchers.IO) {
                queue.poll()
            } ?: run {
                delay(50)
                return@run null
            } ?: continue

            // Resource conflict check
            if (hasResourceConflict(task)) {
                // Re-queue with small delay
                delay(100)
                queue.offer(task)
                continue
            }

            // Parallel vs sequential decision
            val runNow = when {
                task.priority == 0                -> true   // emergency — always
                task.priority == 1                -> true   // critical (Safety)
                task.canRunParallel && runningCount.get() < MAX_PARALLEL_MEDIUM -> true
                !task.canRunParallel && runningCount.get() == 0 -> true
                task.isBackground                -> runningCount.get() < MAX_PARALLEL_MEDIUM
                else                             -> runningCount.get() == 0
            }

            if (!runNow) {
                delay(50)
                queue.offer(task)
                continue
            }

            executeTask(task)
        }
    }

    private fun hasResourceConflict(task: PrioritizedTask): Boolean {
        if (task.priority <= 1) return false     // critical / emergency bypass locks
        return resourceLocks.contains(task.agentType)
    }

    private fun executeTask(task: PrioritizedTask) {
        resourceLocks.add(task.agentType)
        runningCount.incrementAndGet()

        val job = scope.launch {
            Log.d(TAG, "Executing [${task.agentType}] p=${task.priority} '${task.description}'")
            try {
                val result = task.action()
                task.onResult?.invoke(result)
            } catch (e: CancellationException) {
                Log.d(TAG, "Task ${task.taskId} cancelled")
            } catch (e: Throwable) {
                Log.e(TAG, "Task ${task.taskId} error: ${e.message}")
                task.onError?.invoke(e)
            } finally {
                resourceLocks.remove(task.agentType)
                runningCount.decrementAndGet()
                activeJobs.remove(task.taskId)
                if (emergencyFlag.get() && task.priority == 0) {
                    emergencyFlag.set(false)
                }
            }
        }
        activeJobs[task.taskId] = job
    }

    // ── Query helpers ───────────────────────────────────────────────────────

    fun queueSize(): Int = queue.size
    fun activeCount(): Int = runningCount.get()
    fun isIdle(): Boolean = queue.isEmpty() && runningCount.get() == 0
    fun isEmergencyActive(): Boolean = emergencyFlag.get()

    fun getPriorityFor(agentType: AgentCommunication.AgentType): Int =
        AGENT_PRIORITIES[agentType] ?: 5
}
