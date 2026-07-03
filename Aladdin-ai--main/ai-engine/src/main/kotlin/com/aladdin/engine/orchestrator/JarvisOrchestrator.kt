package com.aladdin.engine.orchestrator

import android.util.Log
import com.aladdin.engine.models.*
import com.aladdin.engine.reasoning.ConfidenceScorer
import com.aladdin.engine.reasoning.ResponseMode
import com.aladdin.memory.phase3.GoalManager
import com.aladdin.memory.phase3.HabitLearning
import com.aladdin.memory.phase3.MemoryAnalytics
import com.aladdin.memory.phase3.MemoryDecay
import com.aladdin.memory.phase3.MemoryRouter
import com.aladdin.memory.phase3.ProactiveRecall
import com.aladdin.memory.phase3.RelationshipGraph
import com.aladdin.memory.phase3.UnifiedMemoryResult
import com.aladdin.memory.phase3.UserProfileEvolution
import com.aladdin.memory.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEPRECATED — Phase 4 orchestrator (memory + confidence scoring).
 *
 * This class is superseded by com.aladdin.assistant.orchestrator.JarvisOrchestrator
 * (Phase 5, multi-agent edition), which is the canonical production version.
 *
 * DO NOT add new features here. Migrate callers to the Phase 5 orchestrator.
 * Retained for backward compatibility with Phase 4 memory integration code.
 *
 * Phase 4 — Jarvis Orchestrator (Master Controller).
 *
 * Phase 4 upgrades integrated here:
 *   3.  Better ContextManager  — enriches every message with memory + profile + tasks.
 *   5.  Improved Goal Planner  — tracks priority, deadlines, alternative plans.
 *   6.  Long-term Planning     — surfaces overdue/upcoming goals in session context.
 *   7.  Confidence Scoring     — runs ConfidenceScorer on every response; asks for
 *                                clarification when confidence is low.
 *   9.  Error Recovery         — all subsystem calls are wrapped; never crashes the
 *                                session; partial context is used gracefully.
 *
 * Usage:
 *   1. onSessionStart  — call at conversation start; returns enriched context.
 *   2. onUserMessage   — call for every user message; returns MessageContext4.
 *   3. onAIResponse    — call after every AI reply; updates memory + confidence stats.
 *   4. onSessionEnd    — call at conversation end; persists state.
 *   5. runNightlyMaintenance — schedule via WorkManager.
 */
@Singleton
class JarvisOrchestrator @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryRouter: MemoryRouter,
    private val proactiveRecall: ProactiveRecall,
    private val goalManager: GoalManager,
    private val habitLearning: HabitLearning,
    private val relationshipGraph: RelationshipGraph,
    private val userProfileEvolution: UserProfileEvolution,
    private val memoryDecay: MemoryDecay,
    private val memoryAnalytics: MemoryAnalytics,
    private val confidenceScorer: ConfidenceScorer
) {
    companion object {
        private const val TAG = "JarvisOrchestrator"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentSessionId: String? = null
    private val conversationHistory = mutableListOf<String>()

    // Phase 4 #7 — running confidence stats for the session
    private val sessionConfidenceLog = mutableListOf<Float>()

    // ─── Session Lifecycle ────────────────────────────────────────────────────

    suspend fun onSessionStart(sessionId: String): SessionStartContext = withContext(Dispatchers.IO) {
        currentSessionId = sessionId
        conversationHistory.clear()
        sessionConfidenceLog.clear()

        Log.i(TAG, "Session started: $sessionId")

        val resumeContext    = safeCall("goal resume context") { goalManager.buildResumeContext() } ?: ""
        val habitSummary     = safeCall("habit summary")      { habitLearning.buildDailySummary() } ?: ""
        val personalization  = safeCall("personalization")    { userProfileEvolution.buildPersonalizationContext() } ?: ""
        val predictions      = safeCall("habit predictions")  { habitLearning.predictNow(windowMinutes = 120) } ?: emptyList()

        // Phase 4 #6 — surface overdue goals in the session prompt
        val overdueGoals = safeCall("overdue goals") { goalManager.getActiveGoals().filter { g ->
            g.dueAtMs != null && g.dueAtMs < System.currentTimeMillis()
        }.take(3) } ?: emptyList()

        val systemContextParts = mutableListOf<String>()

        if (personalization.isNotBlank()) {
            systemContextParts.add("=== USER PROFILE ===\n$personalization")
        }
        if (resumeContext.isNotBlank()) {
            systemContextParts.add(resumeContext)
        }
        if (overdueGoals.isNotEmpty()) {
            val overdueStr = overdueGoals.joinToString("\n") { g ->
                "• ${g.title} [OVERDUE — deadline passed]"
            }
            systemContextParts.add("=== OVERDUE GOALS ===\n$overdueStr")
        }
        if (habitSummary.isNotBlank()) {
            systemContextParts.add(habitSummary)
        }
        if (predictions.isNotEmpty()) {
            val predictStr = predictions.take(3).joinToString("\n") { p ->
                "• In ${p.minutesUntil}min: ${p.habit.description} (${(p.confidence * 100).toInt()}% likely)"
            }
            systemContextParts.add("=== PREDICTED UPCOMING ===\n$predictStr")
        }

        val systemContext = systemContextParts.joinToString("\n\n").trim()

        SessionStartContext(
            sessionId              = sessionId,
            systemContextInjection = systemContext,
            hasInterruptedGoals    = resumeContext.isNotBlank()
        )
    }

    // ─── Phase 4 #3 — Enriched Per-Message Context ───────────────────────────

    /**
     * Process a user message and return fully enriched context (Phase 4).
     *
     * Returns [MessageContext4] which includes:
     *   - Combined memory + profile + tasks context
     *   - Phase 4 #7 confidence score for the incoming intent
     *   - Phase 4 #3 deduplicated, priority-ordered context
     */
    suspend fun onUserMessage(
        message: String,
        intent: ClassifiedIntent? = null,
        sessionId: String = currentSessionId ?: "default"
    ): MessageContext4 = withContext(Dispatchers.IO) {
        conversationHistory.add("User: ${message.take(200)}")

        // Fire-and-forget background learning (Phase 4 #9 — errors don't crash)
        scope.launch {
            safeCall("user profile learning")  { userProfileEvolution.learnFromMessage(message) }
            safeCall("habit detection")        { detectAndRecordHabit(message) }
            safeCall("relationship detection") { detectRelationshipMentions(message) }
        }

        // Parallel enrichment
        val proactiveCtx  = safeCall("proactive recall")  { proactiveRecall.recall(message, conversationHistory, sessionId) }
        val routedResult  = safeCall("memory routing")    { memoryRouter.route(message) }

        val proactiveInj  = proactiveCtx?.let { proactiveRecall.buildInjectionPrompt(it) } ?: ""
        val routedContext = routedResult?.let  { memoryRouter.buildContextString(it) } ?: ""

        // Phase 4 #3 — build priority-ordered combined context
        val activeGoalsCtx = safeCall("active goals") {
            goalManager.getActiveGoals().take(5).joinToString("\n") { g ->
                "• [${g.priority}] ${g.title} (${g.progressPercent}% done)"
            }
        } ?: ""

        val userProfileCtx = safeCall("user profile ctx") {
            userProfileEvolution.buildPersonalizationContext().take(300)
        } ?: ""

        val mergedMemoryContext = buildPriorityContext(
            proactive   = proactiveInj,
            routed      = routedContext,
            profile     = userProfileCtx,
            activeTasks = activeGoalsCtx
        )

        // Phase 4 #7 — compute confidence for the incoming message
        val confidenceScore = confidenceScorer.score(
            intent        = intent,
            memoryContext = mergedMemoryContext,
            toolResults   = emptyMap(),
            llmResponse   = "",
            originalQuery = message
        )

        // Parse goal commands
        val goalCommand = parseGoalCommand(message)
        if (goalCommand != null) {
            safeCall("goal command") { executeGoalCommand(goalCommand) }
        }

        Log.d(TAG, "Message processed: '${message.take(50)}…' " +
              "context=${mergedMemoryContext.length}chars " +
              "confidence=${"%.2f".format(confidenceScore.overall)}")

        MessageContext4(
            message              = message,
            memoryContext        = mergedMemoryContext,
            routedResult         = routedResult,
            goalCommand          = goalCommand,
            sessionId            = sessionId,
            confidenceScore      = confidenceScore,
            responseMode         = confidenceScorer.decideResponseMode(confidenceScore),
            userProfileContext   = userProfileCtx,
            activeGoalsContext   = activeGoalsCtx
        )
    }

    /**
     * Call after every AI response.
     * Phase 4: records confidence, saves to memory, handles low-confidence feedback.
     */
    suspend fun onAIResponse(
        response: String,
        intent: ClassifiedIntent? = null,
        toolResults: Map<String, ToolResult> = emptyMap(),
        sessionId: String = currentSessionId ?: "default"
    ) = withContext(Dispatchers.IO) {
        conversationHistory.add("Jarvis: ${response.take(200)}")

        // Phase 4 #7 — track session confidence
        val cs = confidenceScorer.score(
            intent        = intent,
            memoryContext = "",
            toolResults   = toolResults,
            llmResponse   = response,
            originalQuery = conversationHistory.lastOrNull { it.startsWith("User:") }?.removePrefix("User: ") ?: ""
        )
        sessionConfidenceLog.add(cs.overall)

        val exchangeSummary = buildExchangeSummary(response, cs)

        safeCall("save exchange to memory") {
            memoryRepository.addMemory(
                com.aladdin.memory.model.NewMemory(
                    content    = exchangeSummary,
                    memoryType = com.aladdin.memory.db.entity.MemoryType.CONVERSATION,
                    sessionId  = sessionId,
                    source     = "auto"
                )
            )
        }
    }

    suspend fun onSessionEnd(sessionId: String = currentSessionId ?: "default") =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Session ended: $sessionId (avg confidence=${"%.2f".format(sessionConfidenceLog.average())})")

            safeCall("goal persist on end") {
                val activeGoals = goalManager.getActiveGoals()
                if (activeGoals.isNotEmpty()) {
                    Log.d(TAG, "${activeGoals.size} active goals persist across sessions")
                }
            }

            conversationHistory.clear()
            currentSessionId = null
        }

    // ─── Nightly Maintenance ──────────────────────────────────────────────────

    suspend fun runNightlyMaintenance(): MaintenanceReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "Nightly maintenance started")
        val startMs = System.currentTimeMillis()

        var decayResult   = "skipped"
        var summarized    = 0
        var habitsPromoted = 0
        var profileDecayed = false

        safeCall("decay pass") {
            val decay = memoryDecay.runDecayPass()
            decayResult = "Decayed=${decay.decayed}, Archived=${decay.archived}, Merged=${decay.duplicatesMerged}"
        }

        safeCall("summarise old memories") {
            summarized = memoryRepository.summarizeOldMemories()
        }

        safeCall("promote habits") {
            habitsPromoted = habitLearning.promoteConfirmedHabits()
        }

        safeCall("decay stale preferences") {
            userProfileEvolution.decayStalePreferences()
            profileDecayed = true
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        val report = MaintenanceReport(
            decayResult         = decayResult,
            memoriesSummarized  = summarized,
            habitsPromoted      = habitsPromoted,
            profileUpdated      = profileDecayed,
            elapsedMs           = elapsedMs
        )
        Log.i(TAG, "Nightly maintenance done in ${elapsedMs}ms")
        report
    }

    // ─── On-Demand Queries ─────────────────────────────────────────────────────

    suspend fun getMemoryAnalytics(): String = withContext(Dispatchers.IO) {
        safeCall("memory analytics") { memoryAnalytics.buildReportString() }
            ?: "Analytics temporarily unavailable."
    }

    suspend fun getAllGoals() = withContext(Dispatchers.IO) {
        safeCall("get all goals") { goalManager.getAllGoals() } ?: emptyList()
    }

    suspend fun createGoal(title: String, description: String = "", steps: List<String> = emptyList()) =
        withContext(Dispatchers.IO) {
            goalManager.createGoal(title, description, steps)
        }

    suspend fun addRelationship(fromName: String, relationType: String, toName: String, notes: String = "") =
        withContext(Dispatchers.IO) {
            safeCall("add relationship") {
                relationshipGraph.addRelationship(fromName, relationType = relationType, toName = toName, notes = notes)
            }
        }

    suspend fun getRelationshipsOf(name: String) = withContext(Dispatchers.IO) {
        safeCall("get relationships") { relationshipGraph.getRelationshipsOf(name) } ?: emptyList<Any>()
    }

    suspend fun recordHabit(actionType: String, description: String, location: String? = null) =
        withContext(Dispatchers.IO) {
            safeCall("record habit") { habitLearning.recordAction(actionType, description, location) }
        }

    suspend fun predictNextHabits() = withContext(Dispatchers.IO) {
        safeCall("predict habits") { habitLearning.predictNow() } ?: emptyList<Any>()
    }

    /** Phase 4 #7 — average session confidence (diagnostic). */
    fun getSessionAverageConfidence(): Float =
        if (sessionConfidenceLog.isEmpty()) 1f
        else sessionConfidenceLog.average().toFloat()

    // ─── Phase 4 #3 — Priority Context Builder ───────────────────────────────

    /**
     * Merges and deduplicates context segments in priority order:
     *   1. User profile (highest priority — most personal)
     *   2. Active goals/tasks
     *   3. Proactive recall
     *   4. Routed memory context
     */
    private fun buildPriorityContext(
        proactive: String,
        routed: String,
        profile: String,
        activeTasks: String
    ): String {
        val parts = mutableListOf<String>()
        if (profile.isNotBlank())     parts.add("=== USER PROFILE ===\n${profile.take(400)}")
        if (activeTasks.isNotBlank()) parts.add("=== ACTIVE GOALS ===\n${activeTasks.take(400)}")
        if (proactive.isNotBlank())   parts.add("=== PROACTIVE RECALL ===\n${proactive.take(600)}")
        if (routed.isNotBlank())      parts.add("=== MEMORY ===\n${routed.take(600)}")

        val combined = parts.joinToString("\n\n")
        return deduplicateLines(combined).take(4096)
    }

    /** Remove duplicate lines from combined context. */
    private fun deduplicateLines(text: String): String {
        val seen  = mutableSetOf<String>()
        val lines = text.split("\n")
        return lines.filter { line ->
            val key = line.trim().lowercase()
            key.isBlank() || key.startsWith("===") || seen.add(key)
        }.joinToString("\n")
    }

    // ─── Phase 4 #9 — Safe Call Wrapper ──────────────────────────────────────

    /** Execute [block] with full error isolation — never propagates exceptions. */
    private suspend inline fun <T> safeCall(name: String, crossinline block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "[$name] failed gracefully: ${e.message}")
            null
        }
    }

    // ─── Exchange Summary Builder ─────────────────────────────────────────────

    private fun buildExchangeSummary(response: String, cs: ConfidenceScore): String {
        val lastUser = conversationHistory.lastOrNull { it.startsWith("User:") }
            ?.removePrefix("User: ") ?: ""
        val confidenceLabel = confidenceScorer.label(cs.overall)
        return "[$confidenceLabel confidence] $lastUser → ${response.take(150)}"
    }

    // ─── Habit & Relationship Detection ──────────────────────────────────────

    private suspend fun detectAndRecordHabit(message: String) {
        val lower   = message.lowercase()
        val timeReg = Regex("(?:every|daily|usually|always|routine|habit|typically)\\s+(.{5,40})")
        val match   = timeReg.find(lower) ?: return
        val action  = match.groupValues.getOrNull(1)?.trim() ?: return
        habitLearning.recordAction("routine", action.take(60), null)
    }

    private suspend fun detectRelationshipMentions(message: String) {
        val patterns = listOf(
            Regex("(\\w+) is my (friend|colleague|boss|manager|brother|sister|wife|husband|mother|father|daughter|son)"),
            Regex("(\\w+) works at ([\\w\\s]+)"),
            Regex("(\\w+) lives in ([\\w\\s]+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(message.lowercase()) ?: continue
            val groups = match.groupValues
            if (groups.size >= 3) {
                try {
                    when {
                        groups[2] in listOf("friend","colleague","boss","manager") ->
                            relationshipGraph.addRelationship(
                                fromName = groups[1].replaceFirstChar { it.uppercase() },
                                relationType = "${groups[2].replace(" ", "_")}_of",
                                toName = "User"
                            )
                        message.contains("works at") ->
                            relationshipGraph.addRelationship(
                                fromName = groups[1].replaceFirstChar { it.uppercase() },
                                relationType = RelationshipGraph.RelationType.WORKS_AT,
                                toName = groups[2].trim().replaceFirstChar { it.uppercase() },
                                toType = RelationshipGraph.NodeType.ORGANIZATION
                            )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ─── Goal Command Parser ──────────────────────────────────────────────────

    private data class GoalCommandData(val type: String, val data: String)

    private fun parseGoalCommand(message: String): GoalCommandData? {
        val lower = message.lowercase()
        return when {
            lower.contains("add goal") || lower.contains("new goal") || lower.contains("create goal") -> {
                val title = message.replace(Regex("(?i)add|new|create|goal"), "").trim()
                GoalCommandData("create", title.take(100))
            }
            lower.contains("complete goal") || lower.contains("finished goal") ->
                GoalCommandData("complete", message)
            lower.contains("my goals") || lower.contains("list goals") ->
                GoalCommandData("list", "")
            lower.contains("remind me") && lower.contains("goal") ->
                GoalCommandData("reminder", message)
            else -> null
        }
    }

    private suspend fun executeGoalCommand(cmd: GoalCommandData) {
        when (cmd.type) {
            "create"   -> if (cmd.data.length > 3) goalManager.createGoal(cmd.data)
            "reminder" -> Log.d(TAG, "Goal reminder command captured — scheduling")
            "list"     -> { /* handled in response generation */ }
        }
    }
}

// ─── Phase 4 Data Classes ─────────────────────────────────────────────────────

data class SessionStartContext(
    val sessionId: String,
    val systemContextInjection: String,
    val hasInterruptedGoals: Boolean
)

/**
 * Phase 4 upgrade of [MessageContext] — includes confidence score, response mode,
 * and separated profile/goal context segments.
 */
data class MessageContext4(
    val message: String,
    val memoryContext: String,
    val routedResult: UnifiedMemoryResult?,
    val goalCommand: Any?,
    val sessionId: String,
    val confidenceScore: com.aladdin.engine.models.ConfidenceScore,
    val responseMode: ResponseMode,
    val userProfileContext: String,
    val activeGoalsContext: String
)

/** Kept for backward compatibility. */
data class MessageContext(
    val message: String,
    val memoryContext: String,
    val routedResult: UnifiedMemoryResult,
    val goalCommand: Any?,
    val sessionId: String
)

data class MaintenanceReport(
    val decayResult: String,
    val memoriesSummarized: Int,
    val habitsPromoted: Int,
    val profileUpdated: Boolean,
    val elapsedMs: Long
)
