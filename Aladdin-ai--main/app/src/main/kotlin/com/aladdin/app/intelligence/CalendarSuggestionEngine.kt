package com.aladdin.app.intelligence

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 11 Item 4: Calendar Suggestion Engine ──────────────────────────────

@Singleton
class CalendarSuggestionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "CalendarSuggestion" }

    data class CalendarEvent(
        val id: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val location: String?,
        val description: String?
    )

    data class FreeSlot(
        val startMs: Long,
        val endMs: Long,
        val durationMinutes: Long
    )

    data class MeetingSuggestion(
        val title: String,
        val suggestedStartMs: Long,
        val suggestedEndMs: Long,
        val reason: String
    )

    // ── Load events from Android Calendar provider ────────────────────────────
    suspend fun fetchUpcomingEvents(daysAhead: Int = 7): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val events = mutableListOf<CalendarEvent>()
        try {
            val now  = System.currentTimeMillis()
            val end  = now + TimeUnit.DAYS.toMillis(daysAhead.toLong())
            val uri: Uri = CalendarContract.Events.CONTENT_URI
            val proj = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            )
            val sel  = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val args = arrayOf(now.toString(), end.toString())
            val sort = "${CalendarContract.Events.DTSTART} ASC"

            context.contentResolver.query(uri, proj, sel, args, sort)?.use { cursor ->
                while (cursor.moveToNext()) {
                    events.add(CalendarEvent(
                        id          = cursor.getLong(0),
                        title       = cursor.getString(1) ?: "Untitled",
                        startMs     = cursor.getLong(2),
                        endMs       = cursor.getLong(3),
                        location    = cursor.getString(4),
                        description = cursor.getString(5)
                    ))
                }
            }
            Log.i(TAG, "Loaded ${events.size} events for next $daysAhead days")
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "fetchEvents failed: ${e.message}")
        }
        events
    }

    // ── Find free time slots in the calendar ──────────────────────────────────
    suspend fun detectFreeSlots(
        events: List<CalendarEvent>,
        minSlotMinutes: Int = 30,
        workDayStartHour: Int = 9,
        workDayEndHour: Int = 18
    ): List<FreeSlot> = withContext(Dispatchers.Default) {
        val freeSlots = mutableListOf<FreeSlot>()
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()

        // Check next 5 working days
        for (d in 0..4) {
            cal.timeInMillis = now + TimeUnit.DAYS.toMillis(d.toLong())
            cal.set(Calendar.HOUR_OF_DAY, workDayStartHour)
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)

            val dayStart = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, workDayEndHour)
            val dayEnd = cal.timeInMillis

            val dayEvents = events.filter { it.startMs in dayStart..dayEnd || it.endMs in dayStart..dayEnd }
                .sortedBy { it.startMs }

            var cursor = if (d == 0) maxOf(now, dayStart) else dayStart
            for (ev in dayEvents) {
                val slotEnd = minOf(ev.startMs, dayEnd)
                val durMin  = (slotEnd - cursor) / 60_000
                if (durMin >= minSlotMinutes) {
                    freeSlots.add(FreeSlot(cursor, slotEnd, durMin))
                }
                cursor = maxOf(cursor, ev.endMs)
            }
            if (cursor < dayEnd) {
                val durMin = (dayEnd - cursor) / 60_000
                if (durMin >= minSlotMinutes) {
                    freeSlots.add(FreeSlot(cursor, dayEnd, durMin))
                }
            }
        }
        Log.i(TAG, "Found ${freeSlots.size} free slots")
        freeSlots
    }

    // ── Suggest meeting times ─────────────────────────────────────────────────
    suspend fun suggestMeetings(
        title: String,
        durationMinutes: Int = 60,
        preferredHour: Int = 10
    ): List<MeetingSuggestion> {
        val events    = fetchUpcomingEvents()
        val freeSlots = detectFreeSlots(events, minSlotMinutes = durationMinutes)
        val durMs     = TimeUnit.MINUTES.toMillis(durationMinutes.toLong())

        return freeSlots.filter { it.durationMinutes >= durationMinutes }
            .sortedBy { slot ->
                val slotCal = Calendar.getInstance().apply { timeInMillis = slot.startMs }
                val slotHour = slotCal.get(Calendar.HOUR_OF_DAY)
                Math.abs(slotHour - preferredHour)
            }
            .take(3)
            .map { slot ->
                val startCal = Calendar.getInstance().apply { timeInMillis = slot.startMs }
                val endMs    = slot.startMs + durMs
                MeetingSuggestion(
                    title            = title,
                    suggestedStartMs = slot.startMs,
                    suggestedEndMs   = endMs,
                    reason           = "${slot.durationMinutes}min free window, " +
                                      "${startCal.get(Calendar.HOUR_OF_DAY)}:00 on day+${
                                          ((slot.startMs - System.currentTimeMillis()) / 86_400_000).toInt()}"
                )
            }
    }

    // ── Check for conflicts ───────────────────────────────────────────────────
    suspend fun hasConflict(startMs: Long, endMs: Long): Boolean {
        val events = fetchUpcomingEvents()
        return events.any { ev ->
            (startMs < ev.endMs && endMs > ev.startMs)
        }
    }

    fun formatSuggestions(suggestions: List<MeetingSuggestion>): String {
        if (suggestions.isEmpty()) return "No free slots found in the next 5 working days."
        return suggestions.mapIndexed { i, s ->
            val cal = Calendar.getInstance().apply { timeInMillis = s.suggestedStartMs }
            "${i + 1}. ${java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm a", java.util.Locale.US).format(cal.time)} (${s.reason})"
        }.joinToString("\n")
    }
}
