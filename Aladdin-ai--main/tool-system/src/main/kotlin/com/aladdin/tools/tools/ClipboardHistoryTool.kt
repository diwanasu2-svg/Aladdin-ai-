package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aladdin.tools.tools.BaseTool
import org.json.JSONObject
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Phase 10 — Clipboard History Tool
 * Track, search, pin, and auto-clear sensitive clipboard entries.
 */
@Singleton
class ClipboardHistoryTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "clipboard_history"

    override val name = "clipboard_history"
    override val description = "Clipboard history: track copies, search, pin, auto-clear sensitive data"

    data class ClipEntry(
        val id: String, val text: String, val timestamp: Long,
        var pinned: Boolean = false, val isSensitive: Boolean = false, var label: String = ""
    )

    private val history = ArrayDeque<ClipEntry>(100)
    private val clipboard by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    companion object {
        val SENSITIVE_PATTERNS = listOf(
            Pattern.compile("\\b(?:\\d{4}[\\s-]?){3}\\d{4}\\b"),     // credit card
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),            // SSN
            Pattern.compile("(?i)(password|token|api.?key)\\s*[=:]\\s*\\S+"), // credentials
            Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9_\\-.]{20,}")     // JWT/tokens
        )
    }

    init {
        // Listen for clipboard changes
        clipboard.addPrimaryClipChangedListener {
            val clip = clipboard.primaryClip ?: return@addPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return@addPrimaryClipChangedListener
            addEntry(text)
        }
    }

    private fun addEntry(text: String): ClipEntry {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val sensitive = SENSITIVE_PATTERNS.any { it.matcher(text).find() }
        val entry = ClipEntry(id, text, System.currentTimeMillis(), isSensitive = sensitive)
        // Remove duplicate
        history.removeAll { it.id == id }
        if (history.size >= 100) history.removeFirst()
        history.addLast(entry)
        return entry
    }

    fun copyToClipboard(text: String, label: String = "aladdin"): ToolResult {
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            val entry = addEntry(text)
            ToolResult.success(id, JSONObject().apply {
                put("copied", true); put("id", entry.id)
                put("length", text.length); put("is_sensitive", entry.isSensitive)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Copy error") }
    }

    fun getCurrentClipboard(): ToolResult {
        return try {
            val clip = clipboard.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            ToolResult.success(id, JSONObject().apply {
                put("text", text); put("length", text.length)
                put("is_sensitive", SENSITIVE_PATTERNS.any { it.matcher(text).find() })
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Get clipboard error") }
    }

    fun getHistory(limit: Int = 20, includeSensitive: Boolean = false): ToolResult {
        val items = history.toList().takeLast(limit).reversed().map { entry ->
            JSONObject().apply {
                put("id", entry.id)
                put("text", if (entry.isSensitive && !includeSensitive) "[REDACTED]" else entry.text.take(100))
                put("preview", if (entry.isSensitive && !includeSensitive) "[SENSITIVE]" else entry.text.take(50))
                put("pinned", entry.pinned); put("label", entry.label)
                put("timestamp", entry.timestamp); put("is_sensitive", entry.isSensitive)
            }.toString()
        }
        return ToolResult.success(id, JSONObject().apply {
            put("history", items); put("count", items.size)
        }.toString())
    }

    fun pinEntry(id: String, label: String = ""): ToolResult {
        val entry = history.firstOrNull { it.id == id }
            ?: return ToolResult.error(id, "Entry not found: $id")
        entry.pinned = true
        if (label.isNotBlank()) entry.label = label
        return ToolResult.success(id, JSONObject().apply { put("pinned", id); put("label", entry.label) }.toString())
    }

    fun searchHistory(query: String, limit: Int = 10): ToolResult {
        val q = query.lowercase()
        val results = history.filter {
            !it.isSensitive && (it.text.lowercase().contains(q) || it.label.lowercase().contains(q))
        }.takeLast(limit).reversed()
        return ToolResult.success(id, JSONObject().apply {
            put("results", results.map { e ->
                JSONObject().put("id", e.id).put("text", e.text.take(80)).put("pinned", e.pinned).toString()
            })
            put("count", results.size)
        }.toString())
    }

    fun clearSensitive(): ToolResult {
        val before = history.size
        history.removeAll { it.isSensitive && !it.pinned }
        val removed = before - history.size
        // Clear clipboard if sensitive
        val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        if (SENSITIVE_PATTERNS.any { it.matcher(clip).find() }) {
            clipboard.setPrimaryClip(ClipData.newPlainText("cleared", ""))
        }
        return ToolResult.success(id, JSONObject().apply {
            put("cleared_items", removed); put("remaining", history.size)
        }.toString())
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "history")) {
            "copy" -> copyToClipboard((params["text"] ?: return ToolResult.error(id, "Missing required parameter: " + "text")), (params["label"] ?: "aladdin"))
            "get" -> getCurrentClipboard()
            "history" -> getHistory((params["limit"]?.toIntOrNull() ?: 20), (params["include_sensitive"]?.toBoolean() ?: false))
            "pin" -> pinEntry((params["id"] ?: return ToolResult.error(id, "Missing required parameter: " + "id")), (params["label"] ?: ""))
            "search" -> searchHistory((params["query"] ?: return ToolResult.error(id, "Missing required parameter: " + "query")), (params["limit"]?.toIntOrNull() ?: 10))
            "clear_sensitive" -> clearSensitive()
            else -> ToolResult.error(id, "Unknown clipboard action: $action")
        }
    }
}
