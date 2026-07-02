package com.aladdin.engine.autonomy

import android.util.Log
import com.aladdin.engine.models.*
import com.aladdin.engine.planner.GoalTracker
import com.aladdin.engine.planner.HTNPlanner
import com.aladdin.engine.reasoning.ReflectionResult
import com.aladdin.engine.reasoning.SelfReflector
import com.aladdin.engine.tools.ToolExecutor
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Autonomous execution engine — runs plans without user confirmation.
 *
 * Loop:
 *   1. Get next executable task from plan
 *   2. Validate parameters (ask ContextManager to fill gaps if possible)
 *   3. Execute tool via [ToolExecutor] with exponential backoff retry
 *   4. Update task status and goal tracker
 *   5. If task failed and retries exhausted → trigger [HTNPlanner.replan]
 *   6. After all tasks complete → invoke [SelfReflector]
 *   7. If reflection says response is inadequate → auto-correct and re-run
 *   8. Return final [ExecutionResult]
 *
 * Exponential backoff:
 *   delay = min(baseDelay × 2^attempt, maxDelay)
 *   Default: 500ms, 1s, 2s, 4s, 8s, 16s (cap)
 */
@Singleton
class AutonomyEngine @Inject constructor(
    private val toolExecutor: ToolExecutor,
    private val htnPlanner: HTNPlanner,
    private val goalTracker: GoalTracker,
    private val selfReflector: SelfReflector
) {
    companion object {
        private const val TAG = "AutonomyEngine"
        private const val BASE_DELAY_MS = 500L
        private const val MAX_DELAY_MS = 16_000L
        private const val MAX_AUTO_CORRECTIONS = 2
        private const val MAX_REPLAN_ATTEMPTS = 2
    }

    // ─── Plan Execution ───────────────────────────────────────────────────────

    /**
     * Execute [plan] fully autonomously. Emits progress via [onProgress].
     *
     * @param plan       The plan to execute
     * @param goalId     Associated goal (optional, for progress tracking)
     * @param onProgress Callback with (taskName, progressFraction)
     */
    suspend fun execute(
        plan: Plan,
        goalId: String? = null,
        onProgress: ((String, Float) -> Unit)? = null
    ): ExecutionResult {
        Log.i(TAG, "Starting autonomous execution: planId=${plan.id} tasks=${plan.tasks.size}")

        var currentPlan = plan.copy(status = PlanStatus.EXECUTING)
        goalTracker.trackPlan(currentPlan, goalId)

        var replanAttempts = 0
        val executedResults = mutableMapOf<String, ToolResult>()
        val taskOutputs = mutableMapOf<String, String>()

        val executionOrder = htnPlanner.getExecutionOrder(currentPlan)

        for (task in executionOrder) {
            if (task.status != TaskStatus.PENDING) continue

            Log.d(TAG, "Executing task: '${task.name}' toolId=${task.toolId}")
            onProgress?.invoke(task.name, htnPlanner.computeProgress(currentPlan))

            // Enrich parameters with outputs from previous tasks
            val enrichedTask = enrichParameters(task, taskOutputs)

            // Execute with retry
            val result = executeWithRetry(enrichedTask)
            executedResults[task.id] = result

            if (result.success) {
                taskOutputs[task.id] = result.output
                val updatedTask = enrichedTask.copy(
                    status = TaskStatus.COMPLETED,
                    result = result.output,
                    updatedAt = System.currentTimeMillis()
                )
                goalTracker.updateTask(currentPlan.id, updatedTask)
                currentPlan = rebuildPlan(currentPlan, updatedTask)
            } else {
                Log.w(TAG, "Task failed: '${task.name}' — ${result.error}")

                if (replanAttempts < MAX_REPLAN_ATTEMPTS) {
                    replanAttempts++
                    Log.i(TAG, "Replanning (attempt $replanAttempts) after failure of '${task.name}'")
                    currentPlan = htnPlanner.replan(currentPlan, task.id, result.error ?: "unknown error")
                    goalTracker.updatePlan(currentPlan)
                } else {
                    val failedTask = enrichedTask.copy(
                        status = TaskStatus.FAILED,
                        errorMessage = result.error,
                        updatedAt = System.currentTimeMillis()
                    )
                    goalTracker.updateTask(currentPlan.id, failedTask)
                    currentPlan = rebuildPlan(currentPlan, failedTask)
                }
            }
        }

        // Compute final plan state
        val finalProgress = htnPlanner.computeProgress(currentPlan)
        val failedTasks = currentPlan.tasks.filter { it.status == TaskStatus.FAILED }
        val finalStatus = when {
            finalProgress >= 1f -> PlanStatus.COMPLETED
            failedTasks.isNotEmpty() -> PlanStatus.FAILED
            else -> PlanStatus.EXECUTING
        }
        currentPlan = currentPlan.copy(
            status = finalStatus,
            progress = finalProgress,
            updatedAt = System.currentTimeMillis()
        )
        goalTracker.updatePlan(currentPlan)

        // Build raw response from task outputs
        val rawResponse = buildResponse(currentPlan, taskOutputs)

        onProgress?.invoke("Complete", finalProgress)
        Log.i(TAG, "Execution complete: progress=$finalProgress status=$finalStatus")

        return ExecutionResult(
            plan = currentPlan,
            rawResponse = rawResponse,
            taskResults = executedResults,
            isSuccess = finalStatus == PlanStatus.COMPLETED,
            failedTaskCount = failedTasks.size
        )
    }

    /**
     * Apply self-reflection and auto-correct the response if quality is low.
     * Fix: when correction is needed, re-execute the plan with enriched parameters
     * from the reflection rather than just swapping the response string.
     */
    suspend fun reflectAndCorrect(
        result: ExecutionResult,
        originalQuery: String,
        correctionAttempt: Int = 0
    ): Pair<ExecutionResult, ReflectionResult> {
        val reflection = selfReflector.reflectOnPlan(
            plan = result.plan,
            response = result.rawResponse,
            originalQuery = originalQuery
        )

        if (!reflection.isAcceptable && correctionAttempt < MAX_AUTO_CORRECTIONS) {
            Log.i(TAG, "Auto-correcting (attempt ${correctionAttempt + 1}/$MAX_AUTO_CORRECTIONS): ${reflection.corrections}")

            // Build corrected plan by injecting reflection feedback into task parameters
            val correctionContext = mapOf(
                "correction_hint" to reflection.corrections.take(5).joinToString("; ").take(400),
                "previous_response" to result.rawResponse.take(300),
                "correction_attempt" to correctionAttempt.toString()
            )
            val correctedTasks = result.plan.tasks.map { task ->
                if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED) {
                    task.copy(
                        status = TaskStatus.PENDING,
                        parameters = task.parameters + correctionContext,
                        updatedAt = System.currentTimeMillis()
                    )
                } else task
            }
            val correctedPlan = result.plan.copy(
                tasks = correctedTasks,
                status = PlanStatus.EXECUTING,
                updatedAt = System.currentTimeMillis()
            )
            val reExecutedResult = execute(correctedPlan)
            return reflectAndCorrect(reExecutedResult, originalQuery, correctionAttempt + 1)
        }

        return Pair(result, reflection)
    }

    // ─── Task-level Retry ─────────────────────────────────────────────────────

    private suspend fun executeWithRetry(task: Task, maxRetries: Int = task.maxRetries): ToolResult {
        var lastResult: ToolResult? = null
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                val delayMs = exponentialDelay(attempt)
                Log.i(TAG, "Retry $attempt/$maxRetries for '${task.name}' after ${delayMs}ms")
                delay(delayMs)
            }
            val result = toolExecutor.execute(task, maxRetries = 0) // single attempt here
            if (result.success) return result
            lastResult = result
        }
        return lastResult ?: ToolResult(toolId = task.toolId ?: "none", success = false, error = "No attempts made")
    }

    // ─── Parameter Enrichment ─────────────────────────────────────────────────

    /** Propagate outputs from completed tasks into pending task parameters. */
    private fun enrichParameters(task: Task, taskOutputs: Map<String, String>): Task {
        if (taskOutputs.isEmpty()) return task
        val enriched = task.parameters.toMutableMap()
        // Inject the most recent tool output as "previous_result" for chained tasks
        taskOutputs.values.lastOrNull()?.let { enriched["previous_result"] = it.take(500) }
        return task.copy(parameters = enriched)
    }

    // ─── Response Builder ─────────────────────────────────────────────────────

    private fun buildResponse(plan: Plan, taskOutputs: Map<String, String>): String {
        // The "response.generate" tool output is the final user-facing response
        val responseTask = plan.tasks.lastOrNull { it.toolId == "response.generate" }
        if (responseTask != null) {
            taskOutputs[responseTask.id]?.let { return it }
        }
        // Fallback: use last successful task output
        return taskOutputs.values.lastOrNull()
            ?: "I completed the requested actions. Is there anything else I can help you with?"
    }

    private fun rebuildPlan(plan: Plan, updatedTask: Task): Plan {
        val tasks = plan.tasks.map { if (it.id == updatedTask.id) updatedTask else it }
        return plan.copy(tasks = tasks, updatedAt = System.currentTimeMillis())
    }

    private fun exponentialDelay(attempt: Int): Long =
        min((BASE_DELAY_MS * 2.0.pow(attempt)).toLong(), MAX_DELAY_MS)
}

/** Result of a complete plan execution. */
data class ExecutionResult(
    val plan: Plan,
    val rawResponse: String,
    val taskResults: Map<String, ToolResult>,
    val isSuccess: Boolean,
    val failedTaskCount: Int
)
