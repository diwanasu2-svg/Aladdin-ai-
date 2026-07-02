import dagger.hilt.android.qualifiers.ApplicationContext

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Phase 5 – Planner Agent (High Priority)
 *
 * Responsibilities:
 *  - Decompose user tasks into ordered subtasks
 *  - Identify dependencies between subtasks
 *  - Decide execution order (parallel vs sequential)
 *  - Create a detailed execution plan
 *  - Monitor plan progress
 *  - Adapt the plan when subtasks fail
 */
@Singleton
class PlannerAgent @Inject constructor(
    private val memoryAgent: MemoryAgent,
    private val safetyAgent: SafetyAgent
) {
    companion object {
        private const val TAG = "PlannerAgent"
    }

    // ── Models ────────────────────────────────────────────────────────────────

    enum class SubtaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED }
    enum class ExecutionMode  { SEQUENTIAL, PARALLEL }

    data class Subtask(
        val id: String = UUID.randomUUID().toString(),
        val description: String,
        val agentType: AgentCommunication.AgentType,
        val dependencies: List<String> = emptyList(),       // IDs of subtasks that must complete first
        val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
        var status: SubtaskStatus = SubtaskStatus.PENDING,
        var result: Any? = null,
        var failureReason: String? = null,
        val estimatedDurationMs: Long = 2000L,
        val retryCount: Int = 0,
        val maxRetries: Int = 2
    )

    data class ExecutionPlan(
        val id: String = UUID.randomUUID().toString(),
        val goal: String,
        val subtasks: List<Subtask>,
        val createdAt: Long = System.currentTimeMillis(),
        var status: PlanStatus = PlanStatus.CREATED,
        var currentPhaseIndex: Int = 0
    )

    enum class PlanStatus { CREATED, IN_PROGRESS, COMPLETED, FAILED, PARTIALLY_COMPLETED }

    data class PlanProgress(
        val planId: String,
        val total: Int,
        val completed: Int,
        val failed: Int,
        val inProgress: Int,
        val percentComplete: Float,
        val currentPhase: String,
        val isBlocked: Boolean
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val plans = mutableMapOf<String, ExecutionPlan>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.PLANNER ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Planner Agent started")
    }

    // ── Core planning ─────────────────────────────────────────────────────────

    /** Decompose a user goal into an executable plan. */
    fun createPlan(goal: String): ExecutionPlan {
        val safety = safetyAgent.validate(goal)
        if (!safety.isSafe) {
            val blockedPlan = ExecutionPlan(
                goal = goal,
                subtasks = listOf(
                    Subtask(
                        description = "Safety check failed: ${safety.reason}",
                        agentType = AgentCommunication.AgentType.SAFETY,
                        status = SubtaskStatus.FAILED,
                        failureReason = safety.reason
                    )
                ),
                status = PlanStatus.FAILED
            )
            return blockedPlan
        }

        val subtasks = decomposeGoal(goal)
        val plan = ExecutionPlan(goal = goal, subtasks = subtasks)
        plans[plan.id] = plan

        Log.d(TAG, "Plan created [${plan.id}] with ${subtasks.size} subtasks for: $goal")

        // Remember the plan
        memoryAgent.save(
            content = "Execution plan for '$goal': ${subtasks.size} subtasks — ${subtasks.joinToString { it.description }}",
            type = MemoryAgent.MemoryType.EPISODIC,
            tags = listOf("plan", "task"),
            importance = 0.7f
        )

        return plan
    }

    /** Get the next batch of subtasks that are ready to execute (dependencies satisfied). */
    fun getReadySubtasks(planId: String): List<Subtask> {
        val plan = plans[planId] ?: return emptyList()
        val completedIds = plan.subtasks.filter { it.status == SubtaskStatus.COMPLETED }.map { it.id }.toSet()

        return plan.subtasks.filter { subtask ->
            subtask.status == SubtaskStatus.PENDING &&
            subtask.dependencies.all { dep -> dep in completedIds }
        }
    }

    /** Mark a subtask as completed with a result. */
    fun markCompleted(planId: String, subtaskId: String, result: Any? = null) {
        val plan = plans[planId] ?: return
        val subtask = plan.subtasks.find { it.id == subtaskId } ?: return
        subtask.status = SubtaskStatus.COMPLETED
        subtask.result = result
        updatePlanStatus(plan)
        Log.d(TAG, "Subtask completed: ${subtask.description}")
    }

    /** Mark a subtask as failed — attempt retry or adapt the plan. */
    fun markFailed(planId: String, subtaskId: String, reason: String) {
        val plan = plans[planId] ?: return
        val subtask = plan.subtasks.find { it.id == subtaskId } ?: return

        if (subtask.retryCount < subtask.maxRetries) {
            // Reset for retry
            val updated = subtask.copy(
                status = SubtaskStatus.PENDING,
                retryCount = subtask.retryCount + 1,
                failureReason = null
            )
            (plan.subtasks as? MutableList)?.let { list ->
                val idx = list.indexOf(subtask)
                if (idx >= 0) list[idx] = updated
            }
            Log.w(TAG, "Subtask will retry (${updated.retryCount}/${updated.maxRetries}): ${subtask.description}")
        } else {
            subtask.status = SubtaskStatus.FAILED
            subtask.failureReason = reason
            adaptPlan(plan, subtask)
        }

        updatePlanStatus(plan)
    }

    /** Get current progress of a plan. */
    fun getProgress(planId: String): PlanProgress? {
        val plan = plans[planId] ?: return null
        val total = plan.subtasks.size
        val completed = plan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
        val failed = plan.subtasks.count { it.status == SubtaskStatus.FAILED }
        val inProgress = plan.subtasks.count { it.status == SubtaskStatus.IN_PROGRESS }
        val ready = getReadySubtasks(planId)
        val isBlocked = ready.isEmpty() && plan.status == PlanStatus.IN_PROGRESS

        val currentTask = plan.subtasks.firstOrNull { it.status == SubtaskStatus.IN_PROGRESS }
            ?: plan.subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }

        return PlanProgress(
            planId = planId,
            total = total,
            completed = completed,
            failed = failed,
            inProgress = inProgress,
            percentComplete = if (total == 0) 1f else completed.toFloat() / total,
            currentPhase = currentTask?.description ?: "All done",
            isBlocked = isBlocked
        )
    }

    fun getPlan(planId: String): ExecutionPlan? = plans[planId]

    // ── Task decomposition ────────────────────────────────────────────────────

    private fun decomposeGoal(goal: String): List<Subtask> {
        val goalLower = goal.lowercase()
        val subtasks = mutableListOf<Subtask>()

        // 1. Always start with a safety check
        val safetyCheck = Subtask(
            description = "Safety validation of goal",
            agentType = AgentCommunication.AgentType.SAFETY,
            executionMode = ExecutionMode.SEQUENTIAL,
            estimatedDurationMs = 100L
        )
        subtasks.add(safetyCheck)

        // 2. Memory recall — check if we've done similar tasks
        val memoryRecall = Subtask(
            description = "Recall relevant memories and past results",
            agentType = AgentCommunication.AgentType.MEMORY,
            dependencies = listOf(safetyCheck.id),
            executionMode = ExecutionMode.SEQUENTIAL,
            estimatedDurationMs = 300L
        )
        subtasks.add(memoryRecall)

        // 3. Domain-specific subtasks based on goal keywords
        val domainTasks = when {
            containsAny(goalLower, "research", "search", "find", "look up", "information") -> {
                listOf(
                    Subtask(
                        description = "Search internet for: $goal",
                        agentType = AgentCommunication.AgentType.RESEARCH,
                        dependencies = listOf(memoryRecall.id),
                        executionMode = ExecutionMode.SEQUENTIAL,
                        estimatedDurationMs = 5000L
                    ),
                    Subtask(
                        description = "Summarise and rank research results",
                        agentType = AgentCommunication.AgentType.RESEARCH,
                        estimatedDurationMs = 1000L
                    )
                )
            }
            containsAny(goalLower, "code", "program", "write", "implement", "debug", "fix", "build") -> {
                listOf(
                    Subtask(
                        description = "Generate or analyse code for: $goal",
                        agentType = AgentCommunication.AgentType.CODING,
                        dependencies = listOf(memoryRecall.id),
                        executionMode = ExecutionMode.SEQUENTIAL,
                        estimatedDurationMs = 3000L
                    ),
                    Subtask(
                        description = "Generate tests for produced code",
                        agentType = AgentCommunication.AgentType.CODING,
                        estimatedDurationMs = 1500L
                    )
                )
            }
            containsAny(goalLower, "browse", "open", "website", "navigate", "download", "web") -> {
                listOf(
                    Subtask(
                        description = "Navigate to web resource for: $goal",
                        agentType = AgentCommunication.AgentType.BROWSER,
                        dependencies = listOf(memoryRecall.id),
                        executionMode = ExecutionMode.SEQUENTIAL,
                        estimatedDurationMs = 8000L
                    )
                )
            }
            containsAny(goalLower, "image", "picture", "photo", "screenshot", "see", "look", "ocr", "text from") -> {
                listOf(
                    Subtask(
                        description = "Analyse image/visual content for: $goal",
                        agentType = AgentCommunication.AgentType.VISION,
                        dependencies = listOf(memoryRecall.id),
                        executionMode = ExecutionMode.SEQUENTIAL,
                        estimatedDurationMs = 3000L
                    )
                )
            }
            containsAny(goalLower, "remember", "save", "store", "recall", "memory", "forget") -> {
                listOf(
                    Subtask(
                        description = "Memory operation for: $goal",
                        agentType = AgentCommunication.AgentType.MEMORY,
                        dependencies = listOf(memoryRecall.id),
                        executionMode = ExecutionMode.SEQUENTIAL,
                        estimatedDurationMs = 500L
                    )
                )
            }
            else -> {
                // Generic: research + coordinator synthesis
                listOf(
                    Subtask(
                        description = "Gather context for: $goal",
                        agentType = AgentCommunication.AgentType.RESEARCH,
                        dependencies = listOf(memoryRecall.id),
                        executionMode = ExecutionMode.SEQUENTIAL,
                        estimatedDurationMs = 3000L
                    )
                )
            }
        }

        subtasks.addAll(domainTasks)

        // 4. Always end with memory save
        val lastDomainTask = domainTasks.lastOrNull()
        val saveMemory = Subtask(
            description = "Save results to long-term memory",
            agentType = AgentCommunication.AgentType.MEMORY,
            dependencies = lastDomainTask?.let { listOf(it.id) } ?: listOf(memoryRecall.id),
            executionMode = ExecutionMode.SEQUENTIAL,
            estimatedDurationMs = 200L
        )
        subtasks.add(saveMemory)

        return subtasks
    }

    private fun adaptPlan(plan: ExecutionPlan, failedSubtask: Subtask) {
        // Skip dependants of failed subtask
        plan.subtasks.forEach { subtask ->
            if (failedSubtask.id in subtask.dependencies && subtask.status == SubtaskStatus.PENDING) {
                subtask.status = SubtaskStatus.SKIPPED
                Log.w(TAG, "Skipping dependent subtask: ${subtask.description}")
            }
        }
        Log.w(TAG, "Plan adapted after failure of: ${failedSubtask.description}")
    }

    private fun updatePlanStatus(plan: ExecutionPlan) {
        val total = plan.subtasks.size
        val completed = plan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
        val failed = plan.subtasks.count { it.status == SubtaskStatus.FAILED }
        val skipped = plan.subtasks.count { it.status == SubtaskStatus.SKIPPED }

        plan.status = when {
            completed == total                                -> PlanStatus.COMPLETED
            completed + failed + skipped == total && failed > 0 -> PlanStatus.PARTIALLY_COMPLETED
            failed > 0 && getReadySubtasks(plan.id).isEmpty() -> PlanStatus.FAILED
            else                                              -> PlanStatus.IN_PROGRESS
        }
    }

    private fun containsAny(text: String, vararg keywords: String) = keywords.any { text.contains(it) }

    // ── Message handler ───────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        when (msg.type) {
            AgentCommunication.MessageType.TASK_REQUEST -> {
                val goal = msg.payload["goal"]?.toString() ?: return
                val plan = createPlan(goal)
                AgentCommunication.reportResult(
                    sender = AgentCommunication.AgentType.PLANNER,
                    receiver = msg.sender,
                    taskId = msg.taskId,
                    result = mapOf(
                        "planId" to plan.id,
                        "subtaskCount" to plan.subtasks.size,
                        "subtasks" to plan.subtasks.map { mapOf("id" to it.id, "desc" to it.description, "agent" to it.agentType.name) }
                    )
                )
            }
            AgentCommunication.MessageType.STATUS_UPDATE -> {
                val planId = msg.payload["planId"]?.toString() ?: return
                val progress = getProgress(planId)
                AgentCommunication.reportResult(
                    sender = AgentCommunication.AgentType.PLANNER,
                    receiver = msg.sender,
                    taskId = msg.taskId,
                    result = mapOf(
                        "percent" to (progress?.percentComplete ?: 0f),
                        "status" to (plans[planId]?.status?.name ?: "UNKNOWN")
                    )
                )
            }
            else -> {}
        }
    }
}
