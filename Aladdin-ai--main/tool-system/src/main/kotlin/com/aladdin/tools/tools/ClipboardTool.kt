package com.aladdin.tools.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aladdin.tools.db.dao.ClipboardDao
import com.aladdin.tools.db.entity.ClipboardEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clipboard tool.
 *
 * Commands:
 *   read    — read current clipboard content
 *   write   — write text to clipboard
 *   history — show clipboard history (last 50)
 *   search  — search clipboard history
 *   clear   — clear history (optional: older than N days)
 *   delete  — delete specific history entry by ID
 *
 * Params: command, text, entry_id, query, days
 */
@Singleton
class ClipboardTool @Inject constructor(
    private val context: Context,
    private val clipboardDao: ClipboardDao
) : BaseTool {

    override val id = "clipboard"
    override val name = "Clipboard"
    override val description = "Read and write clipboard, with persistent history tracking"

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "read") {
            "write"   -> writeClipboard(params)
            "history" -> getHistory(params)
            "search"  -> searchHistory(params)
            "clear"   -> clearHistory(params)
            "delete"  -> deleteEntry(params)
            else      -> readClipboard()
        }
    }

    private suspend fun readClipboard(): ToolResult = withContext(Dispatchers.Main) {
        val clip = clipboardManager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return@withContext ToolResult.success(id, "(Clipboard is empty)")
        }
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isBlank()) return@withContext ToolResult.success(id, "(Clipboard is empty)")

        // Auto-save to history
        withContext(Dispatchers.IO) {
            clipboardDao.insert(ClipboardEntry(text = text, source = "read"))
        }
        ToolResult.success(id, "📋 Clipboard:\n$text")
    }

    private suspend fun writeClipboard(params: Map<String, String>): ToolResult {
        val text = params["text"] ?: return ToolResult.error(id, "Missing 'text' parameter")
        withContext(Dispatchers.Main) {
            val clip = ClipData.newPlainText("aladdin_clip", text)
            clipboardManager.setPrimaryClip(clip)
        }
        clipboardDao.insert(ClipboardEntry(text = text, source = "write"))
        val preview = text.take(60)
        return ToolResult.success(id, "📋 Copied to clipboard: \"$preview${if (text.length > 60) "…" else ""}\"")
    }

    private suspend fun getHistory(params: Map<String, String>): ToolResult {
        val limit = params["limit"]?.toIntOrNull() ?: 20
        val entries = clipboardDao.getHistory(limit)
        if (entries.isEmpty()) return ToolResult.success(id, "No clipboard history.")
        val sb = StringBuilder("📋 Clipboard History (${entries.size}):\n\n")
        entries.forEachIndexed { i, e ->
            val preview = e.text.take(80).replace('\n', ' ')
            val time = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date(e.createdAt))
            sb.appendLine("[${e.id}] $time: $preview${if (e.text.length > 80) "…" else ""}")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private suspend fun searchHistory(params: Map<String, String>): ToolResult {
        val query = params["query"] ?: return ToolResult.error(id, "Missing query")
        val results = clipboardDao.search(query)
        if (results.isEmpty()) return ToolResult.success(id, "No clipboard entries matching '$query'")
        val sb = StringBuilder("🔍 Clipboard matches (${results.size}):\n\n")
        results.forEach { e ->
            sb.appendLine("[${e.id}] ${e.text.take(100).replace('\n', ' ')}")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private suspend fun clearHistory(params: Map<String, String>): ToolResult {
        val days = params["days"]?.toLongOrNull() ?: 0
        val count = if (days > 0) {
            val cutoff = System.currentTimeMillis() - days * 86_400_000
            clipboardDao.clearBefore(cutoff)
        } else {
            clipboardDao.clearAll()
        }
        return ToolResult.success(id, "🗑 Cleared $count clipboard history entries")
    }

    private suspend fun deleteEntry(params: Map<String, String>): ToolResult {
        val entryId = params["entry_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing entry_id")
        clipboardDao.deleteById(entryId)
        return ToolResult.success(id, "🗑 Clipboard entry $entryId deleted")
    }
}
