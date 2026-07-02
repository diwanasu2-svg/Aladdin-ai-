import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5 – Coordinator Agent (High Priority)
 *
 * Responsibilities:
 *  - Receive user requests and orchestrate the full agent team
 *  - Decide which agents to use for each task
 *  - Coordinate plan execution (sequential + parallel)
 *  - Collect and synthesise results from all agents
 *  - Retry or replace failed agents
 *  - Prepare the final response for the user
 *
 * Example flow:
 *   User → Coordinator → Planner → [Research, Browser, Coding, Vision] → Memory → Final Answer
 */
@Singleton
class CoordinatorAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val codingAgent: CodingAgent,
    private val memoryAgent: MemoryAgent,
    private val safetyAgent: SafetyAgent,
    private val browserAgent: BrowserAgent,
    private val visionAgent: VisionAgent,
    private val prioritySystem: AgentPrioritySystem
) {
    companion object {
        private const val TAG = "CoordinatorAgent"
        private const val MAX_RETRIES = 2
        private const val TASK_TIMEOUT_MS = 30_000L
    }

    // ── Models ────────────────────────────────────────────────────────────────

    data class CoordinationRequest(
        val id: String = UUID.randomUUID().toString(),
        val userInput: String,
        val context: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    data class CoordinationResult(
        val requestId: String,
        val finalResponse: String,
        val agentsUsed: List<AgentCommunication.AgentType>,
        val planId: String,
        val success: Boolean,
        val durationMs: Long,
        val partialResults: Map<String, Any> = emptyMap()
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val activeRequests = ConcurrentHashMap<String, CoordinationRequest>()
    private val resultCollector = ConcurrentHashMap<String, MutableMap<String, Any>>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.COORDINATOR ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleIncomingMessage(msg) }
        }
        Log.d(TAG, "Coordinator Agent started")
    }

    // ── Main coordination entry point ─────────────────────────────────────────

    suspend fun coordinate(userInput: String): CoordinationResult {
        val request = CoordinationRequest(userInput = userInput)
        activeRequests[request.id] = request
        resultCollector[request.id] = mutableMapOf()
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Coordinating request [${request.id}]: ${userInput.take(100)}")

        // Step 1: Safety check (always first — highest priority)
        val safetyReport = safetyAgent.validate(userInput)
        if (!safetyReport.isSafe) {
            cleanup(request.id)
            return CoordinationResult(
                requestId = request.id,
                finalResponse = "I cannot process this request: ${safetyReport.reason}",
                agentsUsed = listOf(AgentCommunication.AgentType.SAFETY),
                planId = "",
                success = false,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Step 2: Create execution plan
        val plan = plannerAgent.createPlan(userInput)
        val agentsUsed = mutableListOf<AgentCommunication.AgentType>()

        // Broadcast plan to shared context
        AgentCommunication.setContext("current_plan_${request.id}", plan)
        AgentCommunication.setContext("user_request_${request.id}", userInput)

        // Step 3: Execute the plan
        try {
            executePlan(plan, request, agentsUsed)
        } catch (e: Exception) {
            Log.e(TAG, "Plan execution error: ${e.message}")
        }

        // Step 4: Synthesise final response
        val results = resultCollector[request.id] ?: emptyMap<String, Any>()
        val finalResponse = synthesiseResponse(userInput, results, plan)

        // Step 5: Save to memory
        memoryAgent.save(
            content = "User asked: '$userInput'\nAnswer: $finalResponse",
            type = MemoryAgent.MemoryType.LONG_TERM,
            tags = listOf("conversation", "result"),
            importance = 0.8f
        )

        cleanup(request.id)
        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Coordination complete in ${duration}ms — agents: ${agentsUsed.map { it.name }}")

        return CoordinationResult(
            requestId = request.id,
            finalResponse = finalResponse,
            agentsUsed = agentsUsed.distinct(),
            planId = plan.id,
            success = true,
            durationMs = duration,
            partialResults = results
        )
    }

    // ── Plan execution ────────────────────────────────────────────────────────

    private suspend fun executePlan(
        plan: PlannerAgent.ExecutionPlan,
        request: CoordinationRequest,
        agentsUsed: MutableList<AgentCommunication.AgentType>
    ) {
        var iterations = 0
        val maxIterations = plan.subtasks.size * (MAX_RETRIES + 1)

        while (true) {
            iterations++
            if (iterations > maxIterations) {
                Log.w(TAG, "Plan execution exceeded max iterations")
                break
            }

            val ready = plannerAgent.getReadySubtasks(plan.id)
            if (ready.isEmpty()) {
                val progress = plannerAgent.getProgress(plan.id)
                if (progress == null || !progress.isBlocked || plan.status == PlannerAgent.PlanStatus.COMPLETED) break
                delay(200)
                continue
            }

            // Execute ready subtasks — group parallel ones, run sequential ones alone
            val parallelGroup = ready.filter { it.executionMode == PlannerAgent.ExecutionMode.PARALLEL }
            val sequentialList = ready.filter { it.executionMode == PlannerAgent.ExecutionMode.SEQUENTIAL }

            // Parallel tasks
            if (parallelGroup.isNotEmpty()) {
                val jobs = parallelGroup.map { subtask ->
                    scope.async {
                        executeSubtask(subtask, plan, request, agentsUsed)
                    }
                }
                jobs.forEach { it.await() }
            }

            // Sequential tasks one by one
            for (subtask in sequentialList) {
                executeSubtask(subtask, plan, request, agentsUsed)
            }

            val progress = plannerAgent.getProgress(plan.id)
            if (plan.status == PlannerAgent.PlanStatus.COMPLETED ||
                plan.status == PlannerAgent.PlanStatus.FAILED ||
                (progress?.isBlocked == true && ready == plannerAgent.getReadySubtasks(plan.id))) {
                break
            }
        }
    }

    private suspend fun executeSubtask(
        subtask: PlannerAgent.Subtask,
        plan: PlannerAgent.ExecutionPlan,
        request: CoordinationRequest,
        agentsUsed: MutableList<AgentCommunication.AgentType>
    ) {
        subtask.status = PlannerAgent.SubtaskStatus.IN_PROGRESS
        agentsUsed.add(subtask.agentType)

        Log.d(TAG, "Executing subtask [${subtask.agentType}]: ${subtask.description}")

        try {
            val result = when (subtask.agentType) {
                AgentCommunication.AgentType.SAFETY -> {
                    val report = safetyAgent.validate(request.userInput)
                    mapOf("safe" to report.isSafe, "reason" to report.reason)
                }
                AgentCommunication.AgentType.MEMORY -> {
                    val memories = memoryAgent.semanticSearch(request.userInput, topK = 3)
                    mapOf("memories" to memories.map { it.content })
                }
                AgentCommunication.AgentType.RESEARCH -> {
                    val report = researchAgent.research(request.userInput)
                    mapOf(
                        "summary" to report.summary,
                        "facts" to report.keyFacts,
                        "confidence" to report.confidence
                    )
                }
                AgentCommunication.AgentType.CODING -> {
                    val generated = codingAgent.generateCode(request.userInput)
                    mapOf("code" to generated.code, "explanation" to generated.explanation)
                }
                AgentCommunication.AgentType.BROWSER -> {
                    val urlMatch = Regex("https?://\\S+").find(request.userInput)
                    if (urlMatch != null) {
                        val page = browserAgent.navigate(urlMatch.value)
                        mapOf("title" to page.title, "text" to page.text.take(500))
                    } else {
                        mapOf("info" to "No URL found in request")
                    }
                }
                AgentCommunication.AgentType.VISION -> {
                    mapOf("info" to "Vision agent ready — provide image data via message bus")
                }
                AgentCommunication.AgentType.PLANNER -> {
                    mapOf("info" to "Planner agent managing execution")
                }
                else -> mapOf("info" to "Agent ${subtask.agentType} executed")
            }

            resultCollector[request.id]?.set(subtask.agentType.name, result)
            plannerAgent.markCompleted(plan.id, subtask.id, result)

            // Notify via bus
            AgentCommunication.reportResult(
                sender = AgentCommunication.AgentType.COORDINATOR,
                receiver = subtask.agentType,
                taskId = request.id,
                result = result
            )
        } catch (e: Exception) {
            Log.e(TAG, "Subtask [${subtask.agentType}] failed: ${e.message}")
            plannerAgent.markFailed(plan.id, subtask.id, e.message ?: "Unknown error")
        }
    }

    // ── Response synthesis ────────────────────────────────────────────────────

    private fun synthesiseResponse(
        userInput: String,
        results: Map<String, Any>,
        plan: PlannerAgent.ExecutionPlan
    ): String = buildString {
        val researchResult = results[AgentCommunication.AgentType.RESEARCH.name] as? Map<*, *>
        val codingResult = results[AgentCommunication.AgentType.CODING.name] as? Map<*, *>
        val browserResult = results[AgentCommunication.AgentType.BROWSER.name] as? Map<*, *>
        val memoryResult = results[AgentCommunication.AgentType.MEMORY.name] as? Map<*, *>
        val visionResult = results[AgentCommunication.AgentType.VISION.name] as? Map<*, *>

        // Primary content
        when {
            researchResult != null -> {
                val summary = researchResult["summary"] as? String ?: ""
                val facts = researchResult["facts"] as? List<*>
                if (summary.isNotBlank()) {
                    appendLine(summary)
                    if (!facts.isNullOrEmpty()) {
                        appendLine()
                        appendLine("Key facts:")
                        facts.take(5).forEach { appendLine("• $it") }
                    }
                }
            }
            codingResult != null -> {
                val code = codingResult["code"] as? String ?: ""
                val explanation = codingResult["explanation"] as? String ?: ""
                if (explanation.isNotBlank()) appendLine(explanation)
                if (code.isNotBlank() && !code.startsWith("//")) {
                    appendLine()
                    appendLine("```")
                    appendLine(code.take(1000))
                    appendLine("```")
                }
            }
            browserResult != null -> {
                val title = browserResult["title"] as? String ?: ""
                val text = browserResult["text"] as? String ?: ""
                if (title.isNotBlank()) appendLine("From: $title")
                if (text.isNotBlank()) appendLine(text.take(500))
            }
            visionResult != null -> {
                val description = visionResult["description"] as? String ?: ""
                if (description.isNotBlank()) appendLine(description)
            }
            else -> {
                // Fallback: use memory context
                val memories = memoryResult?.get("memories") as? List<*>
                if (!memories.isNullOrEmpty()) {
                    appendLine("Based on what I know:")
                    memories.take(2).forEach { appendLine("• $it") }
                } else {
                    appendLine("I have processed your request: \"$userInput\"")
                }
            }
        }

        // Plan summary footer
        val progress = plannerAgent.getProgress(plan.id)
        progress?.let {
            if (it.failed > 0) {
                appendLine()
                appendLine("(Note: ${it.failed} of ${it.total} subtasks encountered issues)")
            }
        }
    }.trim()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun cleanup(requestId: String) {
        activeRequests.remove(requestId)
        resultCollector.remove(requestId)
        AgentCommunication.clearContext("current_plan_$requestId")
        AgentCommunication.clearContext("user_request_$requestId")
    }

    private suspend fun handleIncomingMessage(msg: AgentCommunication.AgentMessage) {
        if (msg.type == AgentCommunication.MessageType.TASK_REQUEST) {
            val userInput = msg.payload["input"]?.toString() ?: return
            val result = coordinate(userInput)
            AgentCommunication.reportResult(
                sender = AgentCommunication.AgentType.COORDINATOR,
                receiver = msg.sender,
                taskId = msg.taskId,
                result = mapOf("response" to result.finalResponse, "success" to result.success)
            )
        }
    }

    fun getActiveRequestCount(): Int = activeRequests.size
}
