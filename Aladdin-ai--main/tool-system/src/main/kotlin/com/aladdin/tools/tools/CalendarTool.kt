package com.aladdin.tools.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar tool — Android ContentProvider.
 *
 * Commands:
 *   list    — list upcoming events in the next N days
 *   create  — create a new event
 *   delete  — delete event by ID
 *   find    — search events by title keyword
 *   update  — update event title/time
 *
 * Params:
 *   command, title, start_time (epoch ms), end_time (epoch ms),
 *   description, location, all_day, calendar_id, event_id, days, query
 */
@Singleton
class CalendarTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "calendar"
    override val name = "Calendar"
    override val description = "Create, read, update and delete calendar events via Android Calendar API"

    companion object {
        private const val TAG = "CalendarTool"
        private val DISPLAY_FMT = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.US)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ToolResult.error(id, "Calendar permission not granted")

        when (params["command"] ?: "list") {
            "create" -> createEvent(params)
            "delete" -> deleteEvent(params)
            "update" -> updateEvent(params)
            "find"   -> findEvents(params)
            else     -> listEvents(params)
        }
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    private fun listEvents(params: Map<String, String>): ToolResult {
        val days = params["days"]?.toIntOrNull() ?: 7
        val now = System.currentTimeMillis()
        val end = now + TimeUnit.DAYS.toMillis(days.toLong())

        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION
        )
        val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
        val selArgs = arrayOf(now.toString(), end.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val sb = StringBuilder("📅 Upcoming events (next $days days):\n\n")
        var count = 0

        context.contentResolver.query(uri, projection, sel, selArgs, sort)?.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: "Untitled"
                val start = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                val allDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1
                val loc = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)) ?: ""

                val timeStr = if (allDay) "All day" else DISPLAY_FMT.format(Date(start))
                sb.appendLine("• $title")
                sb.appendLine("  🕐 $timeStr")
                if (loc.isNotBlank()) sb.appendLine("  📍 $loc")
                sb.appendLine()
                count++
            }
        }

        return if (count == 0) ToolResult.success(id, "No events in the next $days days.")
        else ToolResult.success(id, sb.toString().trim())
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    private fun createEvent(params: Map<String, String>): ToolResult {
        val title = params["title"] ?: return ToolResult.error(id, "Missing event title")
        val startMs = params["start_time"]?.toLongOrNull()
            ?: parseNaturalTime(params["time"] ?: "")
            ?: return ToolResult.error(id, "Missing or invalid start_time (epoch ms)")
        val durationMs = params["duration_minutes"]?.toLongOrNull()?.let { it * 60_000 } ?: 3_600_000
        val endMs = params["end_time"]?.toLongOrNull() ?: (startMs + durationMs)
        val description = params["description"] ?: ""
        val location = params["location"] ?: ""
        val allDay = params["all_day"]?.equals("true", ignoreCase = true) ?: false
        val calendarId = params["calendar_id"]?.toLongOrNull() ?: getDefaultCalendarId()

        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment ?: "unknown"
            val timeStr = DISPLAY_FMT.format(Date(startMs))
            Log.i(TAG, "Created event '$title' id=$eventId at $startMs")
            ToolResult.success(id, "✅ Event created: '$title' on $timeStr (ID: $eventId)")
        } catch (e: Exception) {
            ToolResult.error(id, "Failed to create event: ${e.message}")
        }
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    private fun deleteEvent(params: Map<String, String>): ToolResult {
        val eventId = params["event_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing event_id")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) ToolResult.success(id, "🗑 Event $eventId deleted")
            else ToolResult.error(id, "Event $eventId not found")
        } catch (e: Exception) {
            ToolResult.error(id, "Delete failed: ${e.message}")
        }
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    private fun updateEvent(params: Map<String, String>): ToolResult {
        val eventId = params["event_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing event_id")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val values = ContentValues()
        params["title"]?.let { values.put(CalendarContract.Events.TITLE, it) }
        params["start_time"]?.toLongOrNull()?.let { values.put(CalendarContract.Events.DTSTART, it) }
        params["end_time"]?.toLongOrNull()?.let { values.put(CalendarContract.Events.DTEND, it) }
        params["location"]?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        params["description"]?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }

        return try {
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows > 0) ToolResult.success(id, "✅ Event $eventId updated")
            else ToolResult.error(id, "Event $eventId not found")
        } catch (e: Exception) {
            ToolResult.error(id, "Update failed: ${e.message}")
        }
    }

    // ─── Find ─────────────────────────────────────────────────────────────────

    private fun findEvents(params: Map<String, String>): ToolResult {
        val query = params["query"] ?: return ToolResult.error(id, "Missing query")
        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )
        val sel = "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} = 0"
        val sb = StringBuilder("🔍 Events matching '$query':\n\n")
        var count = 0
        context.contentResolver.query(uri, projection, sel, arrayOf("%$query%"), "${CalendarContract.Events.DTSTART} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1) ?: "Untitled"
                val start = cursor.getLong(2)
                sb.appendLine("• [$id] $title — ${DISPLAY_FMT.format(Date(start))}")
                count++
            }
        }
        return if (count == 0) ToolResult.success(id, "No events matching '$query'")
        else ToolResult.success(id, sb.toString().trim())
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getDefaultCalendarId(): Long {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val proj = arrayOf(CalendarContract.Calendars._ID)
        val sel = "${CalendarContract.Calendars.VISIBLE} = 1"
        context.contentResolver.query(uri, proj, sel, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 1L
    }

    private fun parseNaturalTime(text: String): Long? {
        val now = System.currentTimeMillis()
        val lower = text.lowercase()
        return when {
            "tomorrow" in lower -> now + TimeUnit.DAYS.toMillis(1)
            "tonight"  in lower -> {
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0) }
                cal.timeInMillis
            }
            else -> null
        }
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED
}
