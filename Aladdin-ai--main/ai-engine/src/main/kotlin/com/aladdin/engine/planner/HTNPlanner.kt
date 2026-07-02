package com.aladdin.engine.planner

import android.util.Log
import com.aladdin.engine.models.*
import com.aladdin.engine.tools.ToolRegistry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 — Hierarchical Task Network (HTN) Planner.
 *
 * Phase 4 upgrade #5 — Improved Goal Planner:
 *   - Breaks large tasks into small, manageable subtasks with explicit dependencies.
 *   - Identifies task dependencies and builds a correct execution order.
 *   - Sets priorities on tasks based on goal priority and urgency.
 *   - Tracks progress at fine granularity.
 *   - Creates alternative plans when a task fails.
 *   - Validates the plan before execution (detects impossible dependency cycles).
 */
@Singleton
class HTNPlanner @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val taskDecomposer: TaskDecomposer
) {
    companion object {
        private const val TAG = "HTNPlanner"
        private const val MAX_DEPTH = 6
    }

    // ─── Phase 4 #5 — Plan Generation with Dependencies & Priorities ─────────

    fun plan(
        goal: String,
        intent: ClassifiedIntent,
        context: Map<String, String> = emptyMap(),
        goalPriority: GoalPriority = GoalPriority.NORMAL
    ): Plan {
        Log.i(TAG, "Planning: goal='${goal.take(60)}' intent=${intent.type} priority=$goalPriority")

        val planId  = UUID.randomUUID().toString()
        val params  = intent.entities + context
        val allTasksList = mutableListOf<Task>()

        val rootTask = taskDecomposer.decompose(
            goal        = goal,
            intent      = intent.type,
            parameters  = params,
            depth       = 0,
            accumulator = allTasksList,
            priority    = mapGoalPriorityToTaskPriority(goalPriority)
        )
        allTasksList.add(0, rootTask)

        val bound = allTasksList.map { task ->
            if (task.type == TaskType.PRIMITIVE && task.toolId == null) {
                val tool = toolRegistry.findToolForTask(task, intent.type)
                task.copy(toolId = tool?.id)
            } else task
        }

        val withDependencies = injectDependencies(bound)
        val validated        = validateDependencies(withDependencies)
        val prioritised      = prioritiseTasks(validated, goalPriority)

        val plan = Plan(
            id        = planId,
            goal      = goal,
            intent    = intent.type,
            tasks     = prioritised,
            reasoning = buildReasoning(goal, intent, prioritised, goalPriority)
        )

        Log.i(TAG, "Plan created: ${prioritised.size} tasks, intent=${intent.type}")
        return plan
    }

    // ─── Phase 4 #5 — Dependency-Aware Execution Order ───────────────────────

    /**
     * Returns PRIMITIVE tasks in a safe execution order that respects dependencies.
     * Uses a topological sort — tasks with no unresolved dependencies execute first.
     */
    fun getExecutionOrder(plan: Plan): List<Task> {
        val taskMap = plan.tasks.associateBy { it.id }
        val ordered = mutableListOf<Task>()
        val visited = mutableSetOf<String>()

        fun visit(taskId: String) {
            if (taskId in visited) return
            visited.add(taskId)
            val task = taskMap[taskId] ?: return
            task.dependsOnIds.forEach { depId -> visit(depId) }
            task.subtaskIds.forEach { subId -> visit(subId) }
            if (task.type == TaskType.PRIMITIVE) ordered.add(task)
        }

        plan.tasks
            .filter { it.parentId == null }
            .sortedWith(compareByDescending<Task> { it.priority.ordinal }.thenBy { it.createdAt })
            .forEach { visit(it.id) }

        plan.tasks
            .filter { it.type == TaskType.PRIMITIVE && it.id !in visited }
            .forEach { ordered.add(it) }

        return ordered
    }

    fun nextTask(plan: Plan): Task? {
        val completed = plan.tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
        return getExecutionOrder(plan).firstOrNull { task ->
            task.status == TaskStatus.PENDING &&
            task.dependsOnIds.all { depId -> depId in completed }
        }
    }

    fun computeProgress(plan: Plan): Float {
        val primitives = plan.tasks.filter { it.type == TaskType.PRIMITIVE }
        if (primitives.isEmpty()) return 0f
        val done = primitives.count { it.status == TaskStatus.COMPLETED }
        return done.toFloat() / primitives.size
    }

    // ─── Phase 4 #5 — Alternative Plan on Failure ────────────────────────────

    /**
     * Re-plan after a task fails.
     * Phase 4: tries to provide a genuinely different tool/strategy for the failed task.
     * If the alternative still shares the same toolId, escalates to a stub fallback.
     */
    fun replan(plan: Plan, failedTaskId: String, errorMessage: String): Plan {
        Log.i(TAG, "Replanning after failure of task $failedTaskId: ${errorMessage.take(60)}")

        val failedTask     = plan.tasks.find { it.id == failedTaskId } ?: return plan
        val alternativeTask = taskDecomposer.alternativeDecompose(
            failedTask   = failedTask,
            errorMessage = errorMessage,
            strategy     = selectAlternativeStrategy(failedTask, errorMessage)
        )

        val updatedTasks = plan.tasks.map { t ->
            if (t.id == failedTaskId) alternativeTask else t
        }

        return plan.copy(tasks = updatedTasks, updatedAt = System.currentTimeMillis())
    }

    /**
     * Validate that the plan is feasible (detect circular dependencies).
     * Returns a corrected plan with cycles removed.
     */
    fun validatePlan(plan: Plan): PlanValidationResult {
        val cycle = detectCycle(plan.tasks)
        val missingTools = plan.tasks.filter { it.type == TaskType.PRIMITIVE && it.toolId == null }.map { it.name }
        val warnings = mutableListOf<String>()

        if (cycle != null) warnings.add("Circular dependency detected: $cycle")
        if (missingTools.isNotEmpty()) warnings.add("Tasks missing tool bindings: ${missingTools.take(3)}")

        return PlanValidationResult(
            isValid  = cycle == null,
            warnings = warnings,
            plan     = if (cycle != null) removeCycle(plan) else plan
        )
    }

    // ─── Dependency Injection ─────────────────────────────────────────────────

    /**
     * Injects sequential dependencies for primitives that share the same parent.
     * Step N depends on step N-1 within the same compound task.
     */
    private fun injectDependencies(tasks: List<Task>): List<Task> {
        val taskMap    = tasks.associateBy { it.id }.toMutableMap()
        val byParent   = tasks.groupBy { it.parentId }

        byParent.forEach { (_, siblings) ->
            val primitives = siblings.filter { it.type == TaskType.PRIMITIVE }
            primitives.forEachIndexed { i, task ->
                if (i > 0) {
                    val prev    = primitives[i - 1]
                    val updated = task.copy(dependsOnIds = (task.dependsOnIds + prev.id).distinct())
                    taskMap[task.id] = updated
                }
            }
        }

        return taskMap.values.toList()
    }

    private fun validateDependencies(tasks: List<Task>): List<Task> {
        val idSet = tasks.map { it.id }.toSet()
        return tasks.map { task ->
            val validDeps = task.dependsOnIds.filter { it in idSet }
            if (validDeps.size != task.dependsOnIds.size) task.copy(dependsOnIds = validDeps)
            else task
        }
    }

    private fun prioritiseTasks(tasks: List<Task>, goalPriority: GoalPriority): List<Task> {
        val taskPriority = mapGoalPriorityToTaskPriority(goalPriority)
        return tasks.map { task ->
            if (task.priority == TaskPriority.NORMAL) task.copy(priority = taskPriority)
            else task
        }
    }

    private fun mapGoalPriorityToTaskPriority(gp: GoalPriority): TaskPriority = when (gp) {
        GoalPriority.CRITICAL -> TaskPriority.CRITICAL
        GoalPriority.HIGH     -> TaskPriority.HIGH
        GoalPriority.NORMAL   -> TaskPriority.NORMAL
        GoalPriority.LOW      -> TaskPriority.LOW
    }

    private fun selectAlternativeStrategy(failedTask: Task, errorMessage: String): String {
        val lower = errorMessage.lowercase()
        return when {
            "timeout"   in lower -> "reduce_scope"
            "not found" in lower -> "search_broader"
            "permission" in lower -> "request_permission"
            "network"   in lower -> "use_cached"
            failedTask.retryCount >= 2 -> "escalate_to_llm"
            else -> "retry_with_context"
        }
    }

    private fun detectCycle(tasks: List<Task>): String? {
        val taskMap = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited  = mutableSetOf<String>()

        fun dfs(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited)  return false
            visiting.add(id)
            val task = taskMap[id] ?: return false
            for (dep in task.dependsOnIds) {
                if (dfs(dep)) return true
            }
            visiting.remove(id)
            visited.add(id)
            return false
        }

        for (task in tasks) {
            if (dfs(task.id)) return task.id
        }
        return null
    }

    private fun removeCycle(plan: Plan): Plan {
        val cleaned = plan.tasks.map { task ->
            task.copy(dependsOnIds = emptyList())
        }
        return plan.copy(tasks = cleaned)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildReasoning(
        goal: String,
        intent: ClassifiedIntent,
        tasks: List<Task>,
        priority: GoalPriority
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Goal: $goal")
        sb.appendLine("Intent: ${intent.type} (confidence=${"%.2f".format(intent.confidence)})")
        sb.appendLine("Priority: $priority")
        sb.appendLine("Plan: ${tasks.size} tasks total")
        tasks.filter { it.type == TaskType.PRIMITIVE }.forEachIndexed { i, t ->
            val deps = if (t.dependsOnIds.isNotEmpty()) " [depends on ${t.dependsOnIds.size} task(s)]" else ""
            sb.appendLine("  ${i + 1}. [${t.priority}] ${t.name} → tool=${t.toolId ?: "none"}$deps")
        }
        return sb.toString()
    }
}

data class PlanValidationResult(
    val isValid: Boolean,
    val warnings: List<String>,
    val plan: Plan
)
