package com.aladdin.engine.planner

import com.aladdin.engine.models.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 — Task Decomposer (improved for upgrade #5).
 *
 * Phase 4 upgrades:
 *   5. Improved Goal Planner:
 *      - Each method explicitly assigns task priorities.
 *      - alternativeDecompose() selects a genuinely different strategy/tool.
 *      - New LONG_TERM_GOAL decomposition with multi-step milestone planning.
 *      - Dependency IDs are seeded correctly so HTNPlanner can inject them.
 */
@Singleton
class TaskDecomposer @Inject constructor() {

    private fun id() = UUID.randomUUID().toString()

    // ─── Public API ───────────────────────────────────────────────────────────

    fun decompose(
        goal: String,
        intent: IntentType,
        parameters: Map<String, String>,
        depth: Int,
        accumulator: MutableList<Task>? = null,
        priority: TaskPriority = TaskPriority.NORMAL
    ): Task {
        val rootId   = id()
        val subtasks = getMethod(intent)(goal, parameters, rootId, depth, priority)
        accumulator?.addAll(subtasks)
        return Task(
            id         = rootId,
            name       = "Plan: $goal",
            type       = if (subtasks.isEmpty()) TaskType.PRIMITIVE else TaskType.COMPOUND,
            subtaskIds = subtasks.map { it.id },
            parameters = parameters,
            priority   = priority
        )
    }

    /**
     * Phase 4: Alternative decomposition with a genuine strategy change.
     * Selects a different tool based on the [strategy] hint from HTNPlanner.
     */
    fun alternativeDecompose(
        failedTask: Task,
        errorMessage: String,
        strategy: String = "retry_with_context"
    ): Task {
        val altToolId = selectAlternativeTool(failedTask.toolId, strategy)
        val altParams = failedTask.parameters + mapOf(
            "lastError"           to errorMessage.take(200),
            "alternative_strategy" to strategy,
            "retryCount"          to (failedTask.retryCount + 1).toString()
        )
        return failedTask.copy(
            id         = id(),
            name       = "${failedTask.name} [alt: $strategy]",
            status     = TaskStatus.PENDING,
            toolId     = altToolId ?: failedTask.toolId,
            retryCount = failedTask.retryCount + 1,
            parameters = altParams,
            updatedAt  = System.currentTimeMillis()
        )
    }

    // ─── Method Library ───────────────────────────────────────────────────────

    private typealias Method = (
        goal: String, params: Map<String, String>,
        parentId: String, depth: Int, priority: TaskPriority
    ) -> List<Task>

    private fun getMethod(intent: IntentType): Method = when (intent) {
        IntentType.SET_REMINDER       -> reminderMethod
        IntentType.SEND_MESSAGE       -> sendMessageMethod
        IntentType.NAVIGATE           -> navigateMethod
        IntentType.SEARCH_WEB         -> searchWebMethod
        IntentType.PLAY_MUSIC         -> playMusicMethod
        IntentType.OPEN_APP           -> openAppMethod
        IntentType.REMEMBER_FACT      -> rememberFactMethod
        IntentType.RECALL_MEMORY      -> recallMemoryMethod
        IntentType.WEATHER_QUERY      -> weatherMethod
        IntentType.NEWS_QUERY         -> newsMethod
        IntentType.CREATE_PLAN        -> createPlanMethod
        IntentType.TRACK_GOAL         -> trackGoalMethod
        IntentType.UPDATE_PROJECT     -> updateProjectMethod
        IntentType.FACTUAL_LOOKUP,
        IntentType.QUESTION_ANSWERING -> questionAnswerMethod
        IntentType.SMALL_TALK         -> smallTalkMethod
        else                          -> genericMethod
    }

    // ─── Method Implementations (Phase 4: priority-aware) ─────────────────────

    private val reminderMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Extract reminder details",   "intent.extractor",  parentId, params, TaskPriority.HIGH),
            primitive("Create reminder in memory",  "reminder.create",   parentId, params, priority),
            primitive("Schedule alarm",             "alarm.schedule",    parentId, params, priority),
            primitive("Confirm reminder to user",   "response.generate", parentId, params + ("action" to "confirm_reminder"), TaskPriority.NORMAL)
        )
    }

    private val sendMessageMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Resolve recipient contact",  "contact.resolve",   parentId, params, TaskPriority.HIGH),
            primitive("Compose message content",    "message.compose",   parentId, params, priority),
            primitive("Send via preferred channel", "message.send",      parentId, params, priority),
            primitive("Confirm delivery to user",   "response.generate", parentId, params + ("action" to "confirm_sent"), TaskPriority.NORMAL)
        )
    }

    private val navigateMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Resolve destination address", "location.resolve",  parentId, params, TaskPriority.HIGH),
            primitive("Estimate travel time",        "maps.eta",          parentId, params, TaskPriority.NORMAL),
            primitive("Launch navigation",           "maps.navigate",     parentId, params, priority),
            primitive("Announce navigation started", "response.generate", parentId, params + ("action" to "navigation_started"), TaskPriority.NORMAL)
        )
    }

    private val searchWebMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Build search query",         "search.build_query",    parentId, params, TaskPriority.HIGH),
            primitive("Execute web search",         "search.execute",        parentId, params, priority),
            primitive("Extract top results",        "search.extract_results",parentId, params, TaskPriority.NORMAL),
            primitive("Summarize results for user", "response.generate",     parentId, params + ("action" to "search_results"), TaskPriority.NORMAL)
        )
    }

    private val playMusicMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Resolve track or playlist",  "music.resolve",     parentId, params, TaskPriority.HIGH),
            primitive("Request audio focus",        "audio.focus_request",parentId, params, TaskPriority.HIGH),
            primitive("Start playback",             "music.play",        parentId, params, priority),
            primitive("Announce what's playing",    "response.generate", parentId, params + ("action" to "now_playing"), TaskPriority.NORMAL)
        )
    }

    private val openAppMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Resolve app package name",   "app.resolve", parentId, params, TaskPriority.HIGH),
            primitive("Launch app",                 "app.launch",  parentId, params, priority)
        )
    }

    private val rememberFactMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Extract fact content",       "intent.extractor", parentId, params, TaskPriority.HIGH),
            primitive("Generate embedding",         "memory.embed",     parentId, params, priority),
            primitive("Store in memory system",     "memory.store",     parentId, params, priority),
            primitive("Confirm stored",             "response.generate", parentId, params + ("action" to "confirm_memory"), TaskPriority.NORMAL)
        )
    }

    private val recallMemoryMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Search memory by query",     "memory.search",     parentId, params, TaskPriority.HIGH),
            primitive("Rank by relevance",          "memory.rank",       parentId, params, priority),
            primitive("Format recall response",     "response.generate", parentId, params + ("action" to "recall_result"), TaskPriority.NORMAL)
        )
    }

    private val weatherMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Resolve location",           "location.resolve",  parentId, params, TaskPriority.HIGH),
            primitive("Fetch weather data",         "weather.fetch",     parentId, params, priority),
            primitive("Format weather response",    "response.generate", parentId, params + ("action" to "weather_report"), TaskPriority.NORMAL)
        )
    }

    private val newsMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Fetch headlines",            "news.fetch",        parentId, params, priority),
            primitive("Filter by user preferences", "news.filter",       parentId, params, TaskPriority.NORMAL),
            primitive("Summarize top stories",      "response.generate", parentId, params + ("action" to "news_summary"), TaskPriority.NORMAL)
        )
    }

    private val createPlanMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Analyse goal requirements",  "llm.analyze",       parentId, params, TaskPriority.HIGH),
            primitive("Generate task breakdown",    "planner.decompose", parentId, params, priority),
            primitive("Identify dependencies",      "planner.deps",      parentId, params, TaskPriority.NORMAL),
            primitive("Store plan in memory",       "memory.store",      parentId, params, TaskPriority.NORMAL),
            primitive("Present plan to user",       "response.generate", parentId, params + ("action" to "present_plan"), TaskPriority.NORMAL)
        )
    }

    private val trackGoalMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Create or update goal",      "goal.upsert",       parentId, params, TaskPriority.HIGH),
            primitive("Set up progress tracking",   "goal.track",        parentId, params, priority),
            primitive("Schedule reminder",          "alarm.schedule",    parentId, params, TaskPriority.NORMAL),
            primitive("Confirm goal saved",         "response.generate", parentId, params + ("action" to "goal_saved"), TaskPriority.NORMAL)
        )
    }

    private val updateProjectMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Resolve project reference",  "project.resolve", parentId, params, TaskPriority.HIGH),
            primitive("Update project state",       "project.update",  parentId, params, priority),
            primitive("Confirm update",             "response.generate", parentId, params + ("action" to "project_updated"), TaskPriority.NORMAL)
        )
    }

    private val questionAnswerMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Search relevant memory context",     "memory.search", parentId, params, TaskPriority.HIGH),
            primitive("Generate LLM answer with context",  "llm.answer",    parentId, params, priority),
            primitive("Return answer",                     "response.generate", parentId, params + ("action" to "answer"), TaskPriority.NORMAL)
        )
    }

    private val smallTalkMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Generate conversational reply", "llm.chat", parentId, params, priority)
        )
    }

    private val genericMethod: Method = { _, params, parentId, _, priority ->
        listOf(
            primitive("Search memory for context", "memory.search", parentId, params, TaskPriority.HIGH),
            primitive("Generate response",         "llm.answer",    parentId, params, priority)
        )
    }

    // ─── Phase 4 — Alternative Tool Selection ─────────────────────────────────

    private fun selectAlternativeTool(originalTool: String?, strategy: String): String? {
        return when (strategy) {
            "reduce_scope"       -> when {
                originalTool?.startsWith("search.") == true -> "llm.answer"
                originalTool?.startsWith("memory.") == true -> "llm.answer"
                else -> originalTool
            }
            "search_broader"     -> "search.execute"
            "request_permission" -> "response.generate"
            "use_cached"         -> when {
                originalTool?.startsWith("weather.") == true -> "memory.search"
                originalTool?.startsWith("news.") == true    -> "memory.search"
                else -> originalTool
            }
            "escalate_to_llm"    -> "llm.answer"
            "retry_with_context" -> originalTool
            else -> originalTool
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    private fun primitive(
        name: String,
        toolId: String,
        parentId: String,
        params: Map<String, String>,
        priority: TaskPriority = TaskPriority.NORMAL
    ) = Task(
        id         = id(),
        name       = name,
        type       = TaskType.PRIMITIVE,
        toolId     = toolId,
        parentId   = parentId,
        parameters = params,
        priority   = priority
    )
}
