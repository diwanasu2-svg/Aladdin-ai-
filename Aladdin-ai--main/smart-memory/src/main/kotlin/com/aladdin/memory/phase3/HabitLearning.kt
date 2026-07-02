package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.HabitDao
import com.aladdin.memory.db.entity.HabitEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Habit Learning Engine
 *
 * Observes user's daily routines, identifies frequently repeated actions,
 * detects time-based and location-based habits, and predicts future actions.
 *
 * Examples:
 *   - "Goes to office at 9 AM on weekdays"
 *   - "Sets alarm at 10 PM"
 *   - "Goes shopping on Sunday"
 *   - "Listens to music while commuting"
 *
 * Habit detection algorithm:
 *   1. Collect action events with timestamps and locations
 *   2. Cluster events by hour-of-day and day-of-week
 *   3. If same action occurs ≥ 3 times in similar time window → habit candidate
 *   4. Confidence increases with repetition
 *   5. Time-drifted habits auto-update their time pattern
 */
@Singleton
class HabitLearning @Inject constructor(
    private val habitDao: HabitDao
) {
    companion object {
        private const val TAG = "HabitLearning"
        private const val MIN_OCCURRENCES = 3           // minimum before a habit is declared
        private const val TIME_CLUSTER_WINDOW_MIN = 45  // ±45 minutes = same time slot
        private const val HIGH_CONFIDENCE = 0.8f
        private const val MIN_CONFIDENCE = 0.3f
    }

    // ─── Record Actions ───────────────────────────────────────────────────────

    /**
     * Record a user action. Call this whenever the user does something notable.
     * The engine will automatically detect patterns and create/update habits.
     */
    suspend fun recordAction(
        actionType: String,
        description: String,
        location: String? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // Find existing habit with same action type in similar time window
        val existing = habitDao.getByActionType(actionType)
        val matchingHabit = existing.firstOrNull { habit ->
            abs(habit.minuteOfDay - minuteOfDay) <= TIME_CLUSTER_WINDOW_MIN &&
                (habit.daysOfWeek.isEmpty() || dayOfWeek in habit.daysOfWeek.map { it.toInt() })
        }

        if (matchingHabit != null) {
            // Update existing habit
            val newOccurrences = matchingHabit.occurrenceCount + 1
            val newConfidence = computeConfidence(newOccurrences)
            val updatedDays = (matchingHabit.daysOfWeek + dayOfWeek.toString()).distinct()

            // Update time pattern (rolling average)
            val newMinute = ((matchingHabit.minuteOfDay * matchingHabit.occurrenceCount + minuteOfDay) /
                (matchingHabit.occurrenceCount + 1)).toInt()

            habitDao.update(
                matchingHabit.copy(
                    occurrenceCount = newOccurrences,
                    confidence = newConfidence,
                    daysOfWeek = updatedDays,
                    minuteOfDay = newMinute,
                    lastObservedAt = timestampMs,
                    location = location ?: matchingHabit.location
                )
            )
            Log.d(TAG, "Habit updated: '${matchingHabit.description}' count=$newOccurrences confidence=$newConfidence")
        } else if (existing.isEmpty() || existing.none { it.description.lowercase() == description.lowercase() }) {
            // Create new habit candidate
            val newHabit = HabitEntity(
                actionType = actionType,
                description = description,
                timePattern = formatTimePattern(hourOfDay, minuteOfDay),
                minuteOfDay = minuteOfDay,
                daysOfWeek = listOf(dayOfWeek.toString()),
                location = location,
                occurrenceCount = 1,
                confidence = MIN_CONFIDENCE,
                isConfirmed = false,
                lastObservedAt = timestampMs,
                createdAt = timestampMs
            )
            habitDao.insert(newHabit)
            Log.d(TAG, "New habit candidate: '$description' at ${newHabit.timePattern}")
        }
    }

    // ─── Query Habits ─────────────────────────────────────────────────────────

    fun observeConfirmedHabits(): Flow<List<HabitEntity>> =
        habitDao.observeConfirmed()

    suspend fun getAllHabits(): List<HabitRecord> = withContext(Dispatchers.IO) {
        habitDao.getAll().map { it.toRecord() }
    }

    suspend fun getConfirmedHabits(): List<HabitRecord> = withContext(Dispatchers.IO) {
        habitDao.getConfirmed().map { it.toRecord() }
    }

    suspend fun getHabitsForQuery(query: String): List<HabitRecord> = withContext(Dispatchers.IO) {
        val q = query.lowercase()
        habitDao.getAll()
            .filter { h ->
                h.description.lowercase().contains(q) ||
                    h.actionType.lowercase().contains(q) ||
                    (h.location?.lowercase()?.contains(q) == true)
            }
            .sortedByDescending { it.confidence }
            .map { it.toRecord() }
    }

    // ─── Predictions ──────────────────────────────────────────────────────────

    /**
     * Predict what the user might do NOW or in the next [windowMinutes] minutes
     * based on learned habits.
     */
    suspend fun predictNow(windowMinutes: Int = 60): List<HabitPrediction> = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val currentMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val currentDay = cal.get(Calendar.DAY_OF_WEEK)

        habitDao.getConfirmed()
            .filter { habit ->
                val minuteDiff = habit.minuteOfDay - currentMinute
                minuteDiff in -15..windowMinutes &&
                    (habit.daysOfWeek.isEmpty() || currentDay.toString() in habit.daysOfWeek)
            }
            .sortedBy { abs(it.minuteOfDay - currentMinute) }
            .map { habit ->
                val minutesUntil = (habit.minuteOfDay - currentMinute).coerceAtLeast(0)
                HabitPrediction(
                    habit = habit.toRecord(),
                    minutesUntil = minutesUntil,
                    confidence = habit.confidence
                )
            }
    }

    /**
     * Get habits for a specific day of week (1=Sun, 7=Sat).
     */
    suspend fun getHabitsForDay(dayOfWeek: Int): List<HabitRecord> = withContext(Dispatchers.IO) {
        habitDao.getAll()
            .filter { it.daysOfWeek.isEmpty() || dayOfWeek.toString() in it.daysOfWeek }
            .sortedBy { it.minuteOfDay }
            .map { it.toRecord() }
    }

    // ─── Auto-Confirm ─────────────────────────────────────────────────────────

    /**
     * Promote habit candidates with high enough confidence to confirmed habits.
     * Call this periodically (e.g., weekly).
     */
    suspend fun promoteConfirmedHabits(): Int = withContext(Dispatchers.IO) {
        val candidates = habitDao.getAll()
            .filter { !it.isConfirmed && it.occurrenceCount >= MIN_OCCURRENCES && it.confidence >= 0.6f }
        for (h in candidates) {
            habitDao.update(h.copy(isConfirmed = true))
        }
        Log.i(TAG, "Promoted ${candidates.size} habits to confirmed")
        candidates.size
    }

    // ─── Daily Summary ────────────────────────────────────────────────────────

    /**
     * Build a natural language summary of habits for today.
     */
    suspend fun buildDailySummary(): String = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val todayDay = cal.get(Calendar.DAY_OF_WEEK)
        val habits = getHabitsForDay(todayDay)
        if (habits.isEmpty()) return@withContext ""

        val sb = StringBuilder("=== YOUR TYPICAL ROUTINE FOR TODAY ===\n")
        habits.sortedBy { it.minuteOfDay }.forEach { h ->
            val hour = h.minuteOfDay / 60
            val min = h.minuteOfDay % 60
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = if (hour == 0 || hour == 12) 12 else hour % 12
            sb.appendLine("• ${displayHour}:${min.toString().padStart(2, '0')} $amPm — ${h.description}")
        }
        sb.toString().trim()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun computeConfidence(occurrences: Int): Float =
        when {
            occurrences >= 20 -> HIGH_CONFIDENCE
            occurrences >= 10 -> 0.7f
            occurrences >= MIN_OCCURRENCES -> 0.5f + (occurrences - MIN_OCCURRENCES) * 0.05f
            else -> MIN_CONFIDENCE
        }.coerceIn(MIN_CONFIDENCE, HIGH_CONFIDENCE)

    private fun formatTimePattern(hour: Int, minuteOfDay: Int): String {
        val min = minuteOfDay % 60
        val amPm = if (hour < 12) "AM" else "PM"
        val h = if (hour == 0 || hour == 12) 12 else hour % 12
        return "${h}:${min.toString().padStart(2, '0')} $amPm"
    }

    private fun HabitEntity.toRecord() = HabitRecord(
        id = id,
        actionType = actionType,
        description = description,
        timePattern = timePattern,
        minuteOfDay = minuteOfDay,
        daysOfWeek = daysOfWeek,
        location = location,
        occurrenceCount = occurrenceCount,
        confidence = confidence,
        isConfirmed = isConfirmed,
        lastObservedAt = lastObservedAt,
        createdAt = createdAt
    )
}

data class HabitRecord(
    val id: Long = 0,
    val actionType: String,
    val description: String,
    val timePattern: String,
    val minuteOfDay: Int,
    val daysOfWeek: List<String> = emptyList(),
    val location: String? = null,
    val occurrenceCount: Int = 1,
    val confidence: Float = 0.3f,
    val isConfirmed: Boolean = false,
    val lastObservedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

data class HabitPrediction(
    val habit: HabitRecord,
    val minutesUntil: Int,
    val confidence: Float
)
