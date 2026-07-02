package com.aladdin.tools.manager

import android.util.Log
import com.aladdin.tools.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool Manager — central dispatcher with auto-invocation.
 *
 * Responsibilities:
 *   1. Registry of all tools by their exact tool ID
 *   2. Auto-select tool from natural language intent keywords
 *   3. Auto-extract parameters from user query text
 *   4. Execute tool with structured parameters
 *   5. Return [ToolResult]
 *
 * WeatherTool bug fix: The route ID must match WeatherTool.id exactly ("weather.fetch"),
 * otherwise execute("weather", params) returns "Unknown tool: weather".
 */
@Singleton
class ToolManager @Inject constructor(
    val calculator: CalculatorTool,
    val weather: WeatherTool,
    val calendar: CalendarTool,
    val alarm: AlarmTool,
    val timer: TimerTool,
    val notes: NotesTool,
    val todo: TodoTool,
    val fileReader: FileReaderTool,
    val pdfReader: PdfReaderTool,
    val clipboard: ClipboardTool,
    val systemInfo: SystemInfoTool,
    val appLauncher: AppLauncherTool,
    val musicControl: MusicControlTool,
    val safeShell: SafeShellTool
) {
    companion object {
        private const val TAG = "ToolManager"
    }

    private val allTools: Map<String, BaseTool> by lazy {
        mapOf(
            calculator.id   to calculator,
            weather.id      to weather,    // "weather.fetch"
            calendar.id     to calendar,
            alarm.id        to alarm,
            timer.id        to timer,
            notes.id        to notes,
            todo.id         to todo,
            fileReader.id   to fileReader,
            pdfReader.id    to pdfReader,
            clipboard.id    to clipboard,
            systemInfo.id   to systemInfo,
            appLauncher.id  to appLauncher,
            musicControl.id to musicControl,
            safeShell.id    to safeShell
        )
    }

    private data class ToolRoute(
        val toolId: String,
        val keywords: List<String>,
        val defaultCommand: String? = null
    )

    /**
     * BUGFIX: Route IDs must exactly match the tool's `id` field.
     * WeatherTool.id = "weather.fetch" — route was "weather" (mismatch → "Unknown tool: weather").
     * Fix: use "weather.fetch" as the route key, matching WeatherTool.id.
     */
    private val routes = listOf(
        ToolRoute("calculator",   listOf("calculate", "compute", "math", "evaluate", "+", "-", "×", "÷", "sqrt", "sin", "cos", "log", "factorial"), null),
        ToolRoute("weather.fetch",listOf("weather", "forecast", "temperature", "rain", "sunny", "cloudy", "humidity", "wind speed", "hourly weather"), null),
        ToolRoute("calendar",     listOf("calendar", "event", "schedule", "meeting", "appointment", "add to calendar", "upcoming events"), null),
        ToolRoute("alarm",        listOf("alarm", "wake me", "wake up", "set alarm", "alarms"), "set"),
        ToolRoute("timer",        listOf("timer", "countdown", "count down", "in 5 minutes", "time me", "start timer"), "start"),
        ToolRoute("notes",        listOf("note", "notes", "write down", "jot", "memo", "remember this", "voice note"), "create"),
        ToolRoute("todo",         listOf("todo", "to-do", "task", "add task", "to do list", "checklist", "task list", "remind me to"), "add"),
        ToolRoute("file_reader",  listOf("read file", "open file", "file content", ".txt", ".csv", ".json", ".xml", ".log"), null),
        ToolRoute("pdf_reader",   listOf("pdf", "read pdf", "open pdf", ".pdf"), null),
        ToolRoute("clipboard",    listOf("clipboard", "copy", "paste", "copied text", "what did i copy", "clipboard history"), null),
        ToolRoute("system_info",  listOf("battery", "storage", "memory", "ram", "disk space", "network status", "wifi", "device info", "system info", "phone info"), null),
        ToolRoute("app_launcher", listOf("open ", "launch ", "start app", "open app", "run app"), "launch"),
        ToolRoute("music_control",listOf("play music", "pause music", "next song", "previous song", "volume up", "volume down", "mute", "volume", "music control"), null),
        ToolRoute("safe_shell",   listOf("run command", "shell", "terminal", "execute command", "run shell"), null)
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    suspend fun autoInvoke(query: String): ToolResult = withContext(Dispatchers.Default) {
        val (toolId, defaultCommand) = autoSelect(query)
            ?: return@withContext ToolResult.error("tool_manager", "Could not determine which tool to use for: '$query'")

        val params = autoExtractParams(query, toolId, defaultCommand)
        Log.i(TAG, "Auto-invoke: toolId=$toolId params=$params")
        execute(toolId, params)
    }

    suspend fun execute(toolId: String, params: Map<String, String>): ToolResult {
        val tool = allTools[toolId]
            ?: return ToolResult.error("tool_manager", "Unknown tool: '$toolId'. Available: ${allTools.keys.joinToString()}")

        val startMs = System.currentTimeMillis()
        val result = try {
            tool.execute(params)
        } catch (e: Exception) {
            Log.e(TAG, "Tool '$toolId' threw: ${e.message}", e)
            ToolResult.error(toolId, "Tool error: ${e.message}")
        }
        val duration = System.currentTimeMillis() - startMs
        Log.d(TAG, "Tool '$toolId' completed in ${duration}ms success=${result.success}")
        return result.copy(durationMs = duration)
    }

    fun autoSelect(query: String): Pair<String, String?>? {
        val lower = query.lowercase()
        val scores = routes.map { route ->
            val score = route.keywords.sumOf { kw ->
                if (lower.contains(kw.lowercase())) kw.length else 0
            }
            Triple(route.toolId, route.defaultCommand, score)
        }.filter { it.third > 0 }
            .sortedByDescending { it.third }

        val best = scores.firstOrNull() ?: return null
        return Pair(best.first, best.second)
    }

    fun listTools(): String = allTools.values.joinToString("\n") { t ->
        "• ${t.id}: ${t.description}"
    }

    fun getToolById(id: String): BaseTool? = allTools[id]

    // ─── Parameter Auto-Extraction ────────────────────────────────────────────

    fun autoExtractParams(query: String, toolId: String, defaultCommand: String? = null): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (defaultCommand != null) params["command"] = defaultCommand

        when (toolId) {
            "calculator" -> {
                val expr = query
                    .replace(Regex("^(calculate|compute|evaluate|what is|what's|how much is|solve)\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\?$"), "")
                    .trim()
                if (expr.isNotBlank()) params["expression"] = expr
            }

            "weather.fetch" -> {
                extractLocation(query)?.let { params["location"] = it }
                when {
                    "forecast" in query.lowercase() || "week" in query.lowercase() || "5 day" in query.lowercase() ->
                        params["command"] = "forecast"
                    "hourly" in query.lowercase() || "hour" in query.lowercase() ->
                        params["command"] = "hourly"
                    else -> params["command"] = "current"
                }
                if ("fahrenheit" in query.lowercase() || "imperial" in query.lowercase()) params["units"] = "imperial"
                else params["units"] = "metric"
            }

            "alarm" -> {
                extractTitle(query, listOf("alarm", "wake me up", "wake up"))?.let { params["label"] = it }
                extractTimeMs(query)?.let { params["trigger_at"] = it.toString() }
                extractInMinutes(query)?.let { params["in_minutes"] = it.toString() }
                extractInHours(query)?.let { params["in_hours"] = it.toString() }
                params.getOrPut("command") { "set" }
            }

            "timer" -> {
                extractTitle(query, listOf("timer", "countdown", "count down"))?.let { params["label"] = it }
                extractDurationMinutes(query)?.let { params["duration_minutes"] = it.toString() }
                extractDurationSeconds(query)?.let { params["duration_seconds"] = it.toString() }
                extractDurationHours(query)?.let { params["duration_hours"] = it.toString() }
                params.getOrPut("command") { "start" }
            }

            "notes" -> {
                if ("voice note" in query.lowercase()) {
                    params["command"] = "voice"
                    params["title"] = "Voice Note"
                } else {
                    val lc = query.lowercase()
                    params["command"] = when {
                        "delete" in lc || "remove" in lc -> "delete"
                        "search" in lc || "find" in lc   -> "search"
                        "list" in lc || "show" in lc     -> "list"
                        "pin" in lc                      -> "pin"
                        else                             -> "create"
                    }
                    extractTitle(query, listOf("note", "notes", "write down", "jot down", "memo"))?.let { params["title"] = it }
                    if (params["command"] == "search") params["query"] = extractSearchQuery(query)
                }
            }

            "todo" -> {
                val lc = query.lowercase()
                params["command"] = when {
                    "done" in lc || "complete" in lc || "finish" in lc -> "done"
                    "delete" in lc || "remove" in lc                   -> "delete"
                    "list" in lc || "show" in lc                       -> "list"
                    "overdue" in lc                                     -> "overdue"
                    else                                                -> "add"
                }
                extractTitle(query, listOf("task", "todo", "to do", "to-do", "add", "remind me to"))?.let { params["title"] = it }
            }

            "system_info" -> {
                val lc = query.lowercase()
                params["command"] = when {
                    "battery" in lc                                         -> "battery"
                    "storage" in lc || "disk" in lc || "space" in lc       -> "storage"
                    "memory" in lc || "ram" in lc                           -> "memory"
                    "network" in lc || "wifi" in lc || "internet" in lc    -> "network"
                    "device" in lc || "phone" in lc || "model" in lc       -> "device"
                    else                                                     -> "all"
                }
            }

            "app_launcher" -> {
                extractAppName(query)?.let { params["app_name"] = it }
                val lc = query.lowercase()
                params["command"] = when {
                    "list" in lc || "show apps" in lc   -> "list"
                    "find" in lc || "search" in lc      -> "find"
                    "info" in lc || "about" in lc       -> "info"
                    else                                 -> "launch"
                }
            }

            "music_control" -> {
                val lc = query.lowercase()
                params["command"] = when {
                    "pause" in lc || "stop playing" in lc                          -> "pause"
                    "next" in lc || "skip" in lc                                   -> "next"
                    "previous" in lc || "prev" in lc || "back" in lc               -> "previous"
                    "volume up" in lc || "louder" in lc                            -> "volume_up"
                    "volume down" in lc || "quieter" in lc || "lower volume" in lc -> "volume_down"
                    "mute" in lc || "silence" in lc                                -> "mute"
                    "volume" in lc                                                  -> "volume"
                    else                                                             -> "play"
                }
                extractVolume(query)?.let { params["volume"] = it.toString() }
            }

            "safe_shell" -> {
                val cmd = query
                    .replace(Regex("^(run|execute|shell|terminal|run command|run shell command)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (cmd.isNotBlank()) params["command"] = cmd
            }

            "calendar" -> {
                val lc = query.lowercase()
                params["command"] = when {
                    "create" in lc || "add" in lc || "schedule" in lc || "book" in lc -> "create"
                    "delete" in lc || "cancel" in lc || "remove" in lc                -> "delete"
                    "find" in lc || "search" in lc                                     -> "find"
                    else                                                                -> "list"
                }
                extractTitle(query, listOf("event", "meeting", "appointment", "schedule", "add"))?.let { params["title"] = it }
            }

            "clipboard" -> {
                val lc = query.lowercase()
                params["command"] = when {
                    "copy" in lc || "write" in lc           -> "write"
                    "history" in lc || "past clips" in lc   -> "history"
                    "search" in lc || "find" in lc          -> "search"
                    "clear" in lc                           -> "clear"
                    else                                     -> "read"
                }
                if (params["command"] == "write") {
                    extractAfter(query, listOf("copy ", "write ", "clipboard "))?.let { params["text"] = it }
                }
            }

            "file_reader", "pdf_reader" -> {
                extractFilePath(query)?.let { params["path"] = it }
                val lc = query.lowercase()
                if ("head" in lc || "first" in lc) params["command"] = "head"
                else if ("tail" in lc || "last" in lc) params["command"] = "tail"
                else if ("search" in lc || "find" in lc) params["command"] = "grep"
                else params["command"] = "read"
            }
        }

        return params
    }

    // ─── Extraction Helpers ───────────────────────────────────────────────────

    private fun extractLocation(text: String): String? {
        val patterns = listOf(
            Regex("(?:in|at|for|near)\\s+([A-Z][a-zA-Z]+(?:\\s[A-Z][a-zA-Z]+)?)"),
            Regex("weather\\s+(?:in\\s+)?([A-Z][a-zA-Z]+(?:\\s[A-Z][a-zA-Z]+)?)")
        )
        for (p in patterns) p.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun extractTitle(text: String, triggers: List<String>): String? {
        val lower = text.lowercase()
        var bestIdx = Int.MAX_VALUE
        var bestTrigger = ""
        for (t in triggers) {
            val idx = lower.indexOf(t.lowercase())
            if (idx >= 0 && idx < bestIdx) {
                bestIdx = idx
                bestTrigger = t
            }
        }
        if (bestTrigger.isEmpty()) return null
        val after = text.substring(bestIdx + bestTrigger.length).trim()
            .trimStart('"', '\'').trimEnd('"', '\'')
        return if (after.isNotBlank()) after.take(100) else null
    }

    private fun extractTimeMs(text: String): Long? {
        val lc = text.lowercase()
        val cal = java.util.Calendar.getInstance()
        val p = Regex("(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", RegexOption.IGNORE_CASE)
        val m = p.find(lc) ?: return null
        var hour = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, min)
        cal.set(java.util.Calendar.SECOND, 0)
        if (cal.timeInMillis < System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun extractInMinutes(text: String): Long? =
        Regex("in\\s+(\\d+)\\s+minutes?", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractInHours(text: String): Long? =
        Regex("in\\s+(\\d+)\\s+hours?", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractDurationMinutes(text: String): Long? =
        Regex("(?:for\\s+)?(\\d+)[- ]?(?:min(?:ute)?s?|m\\b)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractDurationSeconds(text: String): Long? =
        Regex("(\\d+)[- ]?(?:seconds?|secs?|s\\b)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractDurationHours(text: String): Long? =
        Regex("(\\d+)[- ]?(?:hours?|hr?s?\\b)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractAppName(text: String): String? {
        val lower = text.lowercase()
        val triggers = listOf("open ", "launch ", "start ", "run ")
        for (t in triggers) {
            val idx = lower.indexOf(t)
            if (idx >= 0) {
                val after = text.substring(idx + t.length).trim()
                    .replace(Regex("\\s*(app|application)$", RegexOption.IGNORE_CASE), "")
                if (after.isNotBlank()) return after.take(50)
            }
        }
        return null
    }

    private fun extractVolume(text: String): Int? =
        Regex("volume\\s+(?:to\\s+)?(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractFilePath(text: String): String? =
        Regex("(/[\\w./\\-]+\\.\\w+|[\\w./\\-]+\\.(?:txt|csv|json|xml|log|pdf|md))", RegexOption.IGNORE_CASE).find(text)?.value

    private fun extractAfter(text: String, triggers: List<String>): String? {
        val lower = text.lowercase()
        for (t in triggers) {
            val idx = lower.indexOf(t)
            if (idx >= 0) return text.substring(idx + t.length).trim().take(500)
        }
        return null
    }

    private fun extractSearchQuery(text: String): String =
        text.replace(Regex("^(search|find|look for|show)\\s*(notes?)?\\s*(?:about|for|with)?\\s*", RegexOption.IGNORE_CASE), "").trim()
}
