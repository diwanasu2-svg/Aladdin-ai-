package com.aladdin.app.briefing

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DailyBriefing — Item 70: Morning scheduler with weather, calendar, news summary.
 * Scheduled daily at 7:00 AM using WorkManager.
 */
@Singleton
class DailyBriefing @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG        = "DailyBriefing"
        private const val WORK_TAG   = "daily_briefing"
        private const val PREFS_KEY  = "daily_briefing_prefs"
    }

    data class BriefingContent(
        val greeting: String,
        val weather: String,
        val calendarEvents: List<String>,
        val newsSummary: String,
        val totalText: String
    )

    // ── Scheduling ────────────────────────────────────────────────────────────

    fun scheduleDaily(hourOfDay: Int = 7, minuteOfHour: Int = 0) {
        val now       = System.currentTimeMillis()
        val calendar  = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
            set(java.util.Calendar.MINUTE, minuteOfHour)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val delay = calendar.timeInMillis - now

        val request = PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        Log.i(TAG, "Daily briefing scheduled for ${hourOfDay}:${minuteOfHour.toString().padStart(2, '0')}")
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        Log.i(TAG, "Daily briefing cancelled")
    }

    // ── Briefing generation ───────────────────────────────────────────────────

    suspend fun generateBriefing(): BriefingContent = withContext(Dispatchers.IO) {
        val greeting = buildGreeting()
        val weather  = fetchWeather()
        val events   = fetchCalendarEvents()
        val news     = fetchNewsSummary()

        val parts = buildList {
            add(greeting)
            if (weather.isNotBlank()) add(weather)
            if (events.isNotEmpty())  add("Today you have ${events.size} event(s): ${events.take(3).joinToString(", ")}.")
            if (news.isNotBlank())    add(news)
        }

        BriefingContent(
            greeting       = greeting,
            weather        = weather,
            calendarEvents = events,
            newsSummary    = news,
            totalText      = parts.joinToString(" ")
        )
    }

    private fun buildGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val salutation = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else      -> "Good evening"
        }
        val day = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US)
            .format(java.util.Date())
        return "$salutation! Today is $day."
    }

    private suspend fun fetchWeather(): String {
        // Placeholder — integrate with a weather API (OpenWeatherMap, etc.)
        return try {
            // Real implementation would call a weather service
            ""
        } catch (_: Exception) { "" }
    }

    private suspend fun fetchCalendarEvents(): List<String> {
        return try {
            val events = mutableListOf<String>()
            val uri = android.provider.CalendarContract.Events.CONTENT_URI
            val startOfDay = System.currentTimeMillis().let {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.timeInMillis
            }
            val endOfDay = startOfDay + 24 * 60 * 60 * 1000L
            val cursor = context.contentResolver.query(
                uri, arrayOf(android.provider.CalendarContract.Events.TITLE),
                "${android.provider.CalendarContract.Events.DTSTART} BETWEEN ? AND ?",
                arrayOf(startOfDay.toString(), endOfDay.toString()),
                android.provider.CalendarContract.Events.DTSTART
            )
            cursor?.use { c ->
                while (c.moveToNext()) { events.add(c.getString(0) ?: "") }
            }
            events.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchNewsSummary(): String = ""

    // ── WorkManager Worker ────────────────────────────────────────────────────

    class BriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            Log.d("BriefingWorker", "Generating daily briefing...")
            return try {
                // DailyBriefing is injected; just log here — real impl would use Hilt Worker
                Log.i("BriefingWorker", "Daily briefing work executed")
                Result.success()
            } catch (e: Exception) {
                Log.e("BriefingWorker", "Briefing failed: ${e.message}")
                Result.retry()
            }
        }
    }
}
