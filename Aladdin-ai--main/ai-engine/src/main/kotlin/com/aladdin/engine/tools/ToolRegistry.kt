package com.aladdin.engine.tools

import android.util.Log
import com.aladdin.engine.models.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry of all tools available to the AI Engine.
 *
 * Each tool has:
 *   - A unique string ID used by [TaskDecomposer]
 *   - A set of [IntentType]s it handles
 *   - A parameter schema for slot-filling validation
 *   - A handler lambda invoked at execution time
 *
 * Tool routing priority:
 *   1. Exact toolId match (set by TaskDecomposer)
 *   2. Intent → tool mapping (fallback for unbound tasks)
 *   3. Generic LLM answer tool
 *
 * Register custom tools at runtime via [register].
 */
@Singleton
class ToolRegistry @Inject constructor() {

    companion object {
        private const val TAG = "ToolRegistry"
    }

    private val tools = mutableMapOf<String, ToolDefinition>()

    // ─── Built-in Tool Definitions ────────────────────────────────────────────

    init {
        registerBuiltins()
    }

    private fun registerBuiltins() {
        register(ToolDefinition(
            id = "llm.answer",
            name = "LLM Answer",
            description = "Generates a language model response for general Q&A",
            intents = setOf(IntentType.QUESTION_ANSWERING, IntentType.FACTUAL_LOOKUP, IntentType.SMALL_TALK),
            parameters = listOf(ToolParameter("query", "User question", required = true))
        ))
        register(ToolDefinition(
            id = "llm.chat",
            name = "LLM Chat",
            description = "Generates a conversational reply",
            intents = setOf(IntentType.SMALL_TALK, IntentType.CLARIFICATION_REQUEST),
            parameters = listOf(ToolParameter("message", "User message", required = true))
        ))
        register(ToolDefinition(
            id = "llm.analyze",
            name = "LLM Analyze",
            description = "Analyzes a goal or plan using the LLM",
            intents = setOf(IntentType.CREATE_PLAN),
            parameters = listOf(ToolParameter("goal", "Goal to analyze", required = true))
        ))
        register(ToolDefinition(
            id = "memory.search",
            name = "Memory Search",
            description = "Searches long-term memory for relevant context",
            intents = setOf(IntentType.RECALL_MEMORY, IntentType.QUESTION_ANSWERING),
            parameters = listOf(ToolParameter("query", "Search query", required = true))
        ))
        register(ToolDefinition(
            id = "memory.store",
            name = "Memory Store",
            description = "Stores a fact or summary in long-term memory",
            intents = setOf(IntentType.REMEMBER_FACT),
            parameters = listOf(ToolParameter("content", "Content to memorize", required = true))
        ))
        register(ToolDefinition(
            id = "memory.embed",
            name = "Memory Embed",
            description = "Generates and stores an embedding for a fact",
            intents = setOf(IntentType.REMEMBER_FACT),
            parameters = listOf(ToolParameter("content", "Content to embed", required = true))
        ))
        register(ToolDefinition(
            id = "memory.rank",
            name = "Memory Rank",
            description = "Ranks recalled memory results by relevance",
            intents = setOf(IntentType.RECALL_MEMORY),
            parameters = listOf(ToolParameter("results", "JSON results list", required = true))
        ))
        register(ToolDefinition(
            id = "reminder.create",
            name = "Create Reminder",
            description = "Creates a reminder entry in the memory system",
            intents = setOf(IntentType.SET_REMINDER),
            parameters = listOf(
                ToolParameter("subject", "Reminder subject", required = true),
                ToolParameter("time", "Trigger time", required = false),
                ToolParameter("duration", "Duration from now", required = false)
            )
        ))
        register(ToolDefinition(
            id = "alarm.schedule",
            name = "Schedule Alarm",
            description = "Schedules an Android alarm for the reminder",
            intents = setOf(IntentType.SET_REMINDER),
            parameters = listOf(
                ToolParameter("trigger_at", "Epoch ms", required = true),
                ToolParameter("title", "Alarm title", required = true)
            )
        ))
        register(ToolDefinition(
            id = "contact.resolve",
            name = "Resolve Contact",
            description = "Looks up a contact by name",
            intents = setOf(IntentType.SEND_MESSAGE),
            parameters = listOf(ToolParameter("recipient", "Contact name", required = true))
        ))
        register(ToolDefinition(
            id = "message.compose",
            name = "Compose Message",
            description = "Composes a message using LLM if needed",
            intents = setOf(IntentType.SEND_MESSAGE),
            parameters = listOf(
                ToolParameter("recipient", "Contact name", required = true),
                ToolParameter("message", "Message body", required = false)
            )
        ))
        register(ToolDefinition(
            id = "message.send",
            name = "Send Message",
            description = "Sends a message via the preferred channel",
            intents = setOf(IntentType.SEND_MESSAGE),
            parameters = listOf(
                ToolParameter("recipient", "Contact name or number", required = true),
                ToolParameter("body", "Message body", required = true),
                ToolParameter("channel", "whatsapp|sms|email", required = false, defaultValue = "sms")
            ),
            requiresConfirmation = false  // autonomy mode
        ))
        register(ToolDefinition(
            id = "location.resolve",
            name = "Resolve Location",
            description = "Geocodes a location name to coordinates",
            intents = setOf(IntentType.NAVIGATE, IntentType.WEATHER_QUERY),
            parameters = listOf(ToolParameter("location", "Place name", required = true))
        ))
        register(ToolDefinition(
            id = "maps.eta",
            name = "Maps ETA",
            description = "Estimates travel time to destination",
            intents = setOf(IntentType.NAVIGATE),
            parameters = listOf(ToolParameter("destination", "Destination address", required = true))
        ))
        register(ToolDefinition(
            id = "maps.navigate",
            name = "Start Navigation",
            description = "Launches navigation to destination",
            intents = setOf(IntentType.NAVIGATE),
            parameters = listOf(ToolParameter("destination", "Destination address", required = true))
        ))
        register(ToolDefinition(
            id = "weather.fetch",
            name = "Fetch Weather",
            description = "Fetches current weather for a location",
            intents = setOf(IntentType.WEATHER_QUERY),
            parameters = listOf(
                ToolParameter("location", "City or coordinates", required = false, defaultValue = "current"),
                ToolParameter("units", "metric|imperial", required = false, defaultValue = "metric")
            )
        ))
        register(ToolDefinition(
            id = "news.fetch",
            name = "Fetch News",
            description = "Fetches top news headlines",
            intents = setOf(IntentType.NEWS_QUERY),
            parameters = listOf(ToolParameter("category", "News category", required = false, defaultValue = "general"))
        ))
        register(ToolDefinition(
            id = "news.filter",
            name = "Filter News",
            description = "Filters news by user preference topics",
            intents = setOf(IntentType.NEWS_QUERY),
            parameters = listOf(ToolParameter("topics", "Comma-separated topics", required = false))
        ))
        register(ToolDefinition(
            id = "search.build_query",
            name = "Build Search Query",
            description = "Optimizes the search query for web search",
            intents = setOf(IntentType.SEARCH_WEB),
            parameters = listOf(ToolParameter("query", "Raw query", required = true))
        ))
        register(ToolDefinition(
            id = "search.execute",
            name = "Execute Web Search",
            description = "Runs web search and returns raw results",
            intents = setOf(IntentType.SEARCH_WEB),
            parameters = listOf(ToolParameter("query", "Optimized query", required = true))
        ))
        register(ToolDefinition(
            id = "search.extract_results",
            name = "Extract Search Results",
            description = "Extracts and ranks top search results",
            intents = setOf(IntentType.SEARCH_WEB),
            parameters = listOf(ToolParameter("raw_results", "Raw results JSON", required = true))
        ))
        register(ToolDefinition(
            id = "music.resolve",
            name = "Resolve Music",
            description = "Resolves a music query to a track or playlist",
            intents = setOf(IntentType.PLAY_MUSIC),
            parameters = listOf(ToolParameter("query", "Music query", required = true))
        ))
        register(ToolDefinition(
            id = "music.play",
            name = "Play Music",
            description = "Starts music playback",
            intents = setOf(IntentType.PLAY_MUSIC),
            parameters = listOf(ToolParameter("track_uri", "Track or playlist URI", required = true))
        ))
        register(ToolDefinition(
            id = "audio.focus_request",
            name = "Request Audio Focus",
            description = "Requests audio focus from Android AudioManager",
            intents = setOf(IntentType.PLAY_MUSIC),
            parameters = emptyList()
        ))
        register(ToolDefinition(
            id = "app.resolve",
            name = "Resolve App",
            description = "Resolves an app name to a package name",
            intents = setOf(IntentType.OPEN_APP),
            parameters = listOf(ToolParameter("app_name", "App name", required = true))
        ))
        register(ToolDefinition(
            id = "app.launch",
            name = "Launch App",
            description = "Launches an app by package name",
            intents = setOf(IntentType.OPEN_APP),
            parameters = listOf(ToolParameter("package_name", "Package name", required = true))
        ))
        register(ToolDefinition(
            id = "goal.upsert",
            name = "Create/Update Goal",
            description = "Creates or updates a user goal",
            intents = setOf(IntentType.TRACK_GOAL),
            parameters = listOf(
                ToolParameter("title", "Goal title", required = true),
                ToolParameter("description", "Goal description", required = false)
            )
        ))
        register(ToolDefinition(
            id = "goal.track",
            name = "Track Goal Progress",
            description = "Sets up progress tracking milestones for a goal",
            intents = setOf(IntentType.TRACK_GOAL),
            parameters = listOf(ToolParameter("goal_id", "Goal ID", required = true))
        ))
        register(ToolDefinition(
            id = "project.resolve",
            name = "Resolve Project",
            description = "Looks up a project by name or ID",
            intents = setOf(IntentType.UPDATE_PROJECT),
            parameters = listOf(ToolParameter("name", "Project name", required = true))
        ))
        register(ToolDefinition(
            id = "project.update",
            name = "Update Project",
            description = "Updates project status or progress",
            intents = setOf(IntentType.UPDATE_PROJECT),
            parameters = listOf(
                ToolParameter("project_id", "Project ID", required = true),
                ToolParameter("status", "New status", required = false),
                ToolParameter("progress", "Progress 0-100", required = false)
            )
        ))
        register(ToolDefinition(
            id = "planner.decompose",
            name = "Planner Decompose",
            description = "Recursively decomposes a plan into subtasks",
            intents = setOf(IntentType.CREATE_PLAN),
            parameters = listOf(ToolParameter("goal", "Goal to decompose", required = true))
        ))
        register(ToolDefinition(
            id = "intent.extractor",
            name = "Intent Parameter Extractor",
            description = "Extracts structured parameters from natural language",
            intents = IntentType.values().toSet(),
            parameters = listOf(ToolParameter("text", "Raw user text", required = true))
        ))
        register(ToolDefinition(
            id = "response.generate",
            name = "Generate Response",
            description = "Generates the final natural-language response to the user",
            intents = IntentType.values().toSet(),
            parameters = listOf(
                ToolParameter("action", "Response action type", required = true),
                ToolParameter("data", "Data to include in response", required = false)
            )
        ))

        Log.i(TAG, "Registered ${tools.size} built-in tools")
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun register(tool: ToolDefinition) {
        tools[tool.id] = tool
    }

    fun getById(id: String): ToolDefinition? = tools[id]

    fun getAll(): List<ToolDefinition> = tools.values.toList()

    fun findToolForIntent(intent: IntentType): List<ToolDefinition> =
        tools.values.filter { intent in it.intents }

    fun findToolForTask(task: Task, intent: IntentType): ToolDefinition? =
        task.toolId?.let { getById(it) }
            ?: findToolForIntent(intent).firstOrNull()

    /** Validate that all required parameters are present for a tool. */
    fun validateParameters(tool: ToolDefinition, params: Map<String, String>): List<String> {
        return tool.parameters
            .filter { it.required && it.name !in params && it.defaultValue == null }
            .map { "Missing required parameter: ${it.name}" }
    }

    /** Fill default values for missing optional parameters. */
    fun fillDefaults(tool: ToolDefinition, params: Map<String, String>): Map<String, String> {
        val result = params.toMutableMap()
        tool.parameters
            .filter { it.name !in result && it.defaultValue != null }
            .forEach { result[it.name] = it.defaultValue!! }
        return result
    }
}
