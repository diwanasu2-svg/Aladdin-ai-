package com.aladdin.tools.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File Reader tool.
 *
 * Reads txt, csv, json, xml, md, log, and properties files.
 * Supports both file path and content URI (from file picker).
 *
 * Commands:
 *   read   — read full file (with optional line range)
 *   head   — read first N lines
 *   tail   — read last N lines
 *   grep   — search for lines matching a pattern
 *   info   — file metadata (size, lines, last modified)
 *
 * Params: command, path, uri, start_line, end_line, lines, pattern, max_chars
 */
@Singleton
class FileReaderTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "file_reader"
    override val name = "File Reader"
    override val description = "Read txt, csv, json, xml, and other text files from device storage"

    companion object {
        private const val TAG = "FileReaderTool"
        private const val MAX_CHARS_DEFAULT = 8000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"]
        val uriStr = params["uri"]

        if (path == null && uriStr == null) {
            return@withContext ToolResult.error(id, "Provide 'path' (file path) or 'uri' (content URI)")
        }

        val text = try {
            if (uriStr != null) readUri(Uri.parse(uriStr))
            else readFile(path!!)
        } catch (e: Exception) {
            return@withContext ToolResult.error(id, "Failed to read file: ${e.message}")
        }

        val maxChars = params["max_chars"]?.toIntOrNull() ?: MAX_CHARS_DEFAULT

        when (params["command"] ?: "read") {
            "head"  -> headLines(text, params["lines"]?.toIntOrNull() ?: 20, maxChars)
            "tail"  -> tailLines(text, params["lines"]?.toIntOrNull() ?: 20, maxChars)
            "grep"  -> grepLines(text, params["pattern"] ?: "", maxChars)
            "info"  -> fileInfo(path, uriStr, text)
            else    -> readRange(text, params, maxChars)
        }
    }

    private fun readRange(text: String, params: Map<String, String>, maxChars: Int): ToolResult {
        val lines = text.lines()
        val start = (params["start_line"]?.toIntOrNull()?.minus(1))?.coerceAtLeast(0) ?: 0
        val end = params["end_line"]?.toIntOrNull()?.coerceAtMost(lines.size) ?: lines.size
        val slice = lines.subList(start, end).joinToString("\n")
        val truncated = if (slice.length > maxChars) slice.take(maxChars) + "\n… [truncated]" else slice
        val ext = params["path"]?.substringAfterLast('.')?.lowercase() ?: "txt"
        val formatted = formatByType(truncated, ext)
        return ToolResult.success(id, "📄 Lines ${ start + 1}–$end (${lines.size} total):\n\n$formatted")
    }

    private fun headLines(text: String, n: Int, maxChars: Int): ToolResult {
        val content = text.lines().take(n).joinToString("\n").take(maxChars)
        return ToolResult.success(id, "📄 First $n lines:\n\n$content")
    }

    private fun tailLines(text: String, n: Int, maxChars: Int): ToolResult {
        val content = text.lines().takeLast(n).joinToString("\n").take(maxChars)
        return ToolResult.success(id, "📄 Last $n lines:\n\n$content")
    }

    private fun grepLines(text: String, pattern: String, maxChars: Int): ToolResult {
        if (pattern.isBlank()) return ToolResult.error(id, "Missing pattern for grep")
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        val matches = text.lines()
            .mapIndexed { i, line -> Pair(i + 1, line) }
            .filter { (_, line) -> regex.containsMatchIn(line) }
        if (matches.isEmpty()) return ToolResult.success(id, "No lines matching '$pattern'")
        val content = matches.take(100).joinToString("\n") { (num, line) -> "$num: $line" }.take(maxChars)
        return ToolResult.success(id, "🔍 ${matches.size} match(es) for '$pattern':\n\n$content")
    }

    private fun fileInfo(path: String?, uriStr: String?, text: String): ToolResult {
        val lines = text.lines().size
        val chars = text.length
        val file = path?.let { File(it) }
        val sizeBytes = file?.length() ?: chars.toLong()
        val lastMod = file?.lastModified()?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it))
        } ?: "unknown"
        return ToolResult.success(id, buildString {
            appendLine("📄 File Info:")
            appendLine("  Path: ${path ?: uriStr ?: "unknown"}")
            appendLine("  Size: ${formatSize(sizeBytes)}")
            appendLine("  Lines: $lines")
            appendLine("  Chars: $chars")
            appendLine("  Modified: $lastMod")
        })
    }

    private fun formatByType(content: String, ext: String): String {
        return when (ext) {
            "json" -> try { prettyJson(content) } catch (_: Exception) { content }
            "csv"  -> formatCsv(content)
            else   -> content
        }
    }

    private fun prettyJson(raw: String): String {
        return if (raw.trimStart().startsWith("[")) {
            JSONArray(raw).toString(2)
        } else {
            JSONObject(raw).toString(2)
        }
    }

    private fun formatCsv(content: String): String {
        val lines = content.lines().take(50)
        val headers = lines.firstOrNull()?.split(",") ?: return content
        val sb = StringBuilder()
        sb.appendLine("Columns: ${headers.joinToString(" | ")}")
        sb.appendLine("───")
        lines.drop(1).forEach { line ->
            val cols = line.split(",")
            sb.appendLine(headers.zip(cols).joinToString(" | ") { (h, v) -> "$h=$v" })
        }
        return sb.toString()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024      -> "%.1f KB".format(bytes / 1024.0)
        else               -> "$bytes B"
    }

    private fun readFile(path: String): String = File(path).readText()

    private fun readUri(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: throw Exception("Could not open URI: $uri")
}
