package com.aladdin.memory.phase3

import android.util.Log
import com.aladdin.memory.db.dao.GoalDao
import com.aladdin.memory.db.entity.GoalEntity
import com.aladdin.memory.db.entity.GoalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-Session Goal Manager
 *
 * Persists user goals in the database so they survive app close and phone restarts.
 * Tracks goal progress, status, and next steps. Supports:
 *   - Creating/updating goals with title, description, steps
 *   - Progress tracking (0–100%)
 *   - Status transitions (ACTIVE → PAUSED → COMPLETED / CANCELLED)
 *   - Resuming interrupted tasks on next session
 *   - Searching goals by semantic similarity
 */
@Singleton
class GoalManager @Inject constructor(
    private val goalDao: GoalDao
) {
    companion object {
        private const val TAG = "GoalManager"
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    suspend fun createGoal(
        title: String,
        description: String = "",
        steps: List<String> = emptyList(),
        priority: Int = 2,
        dueAtMs: Long? = null,
        category: String = "general"
    ): Long = withContext(Dispatchers.IO) {
        val entity = GoalEntity(
            title = title,
            description = description,
            steps = steps,
            completedSteps = emptyList(),
            status = GoalStatus.ACTIVE,
            progressPercent = 0,
            priority = priority,
            dueAtMs = dueAtMs,
            category = category,
            resumeContext = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val id = goalDao.insert(entity)
        Log.i(TAG, "Goal created id=$id title='$title'")
        id
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    fun observeActiveGoals(): Flow<List<GoalEntity>> = goalDao.observeByStatus(GoalStatus.ACTIVE)

    fun observeAll(): Flow<List<GoalEntity>> = goalDao.observeAll()

    suspend fun getById(id: Long): GoalEntity? = withContext(Dispatchers.IO) {
        goalDao.getById(id)
    }

    suspend fun getActiveGoals(): List<GoalRecord> = withContext(Dispatchers.IO) {
        goalDao.getByStatus(GoalStatus.ACTIVE).map { it.toRecord() }
    }

    suspend fun getAllGoals(): List<GoalRecord> = withContext(Dispatchers.IO) {
        goalDao.getAll().map { it.toRecord() }
    }

    /**
     * Search goals by keyword match in title/description.
     * Returns [GoalRecord] list sorted by relevance.
     */
    suspend fun searchGoals(query: String): List<GoalRecord> = withContext(Dispatchers.IO) {
        val q = query.lowercase()
        val all = goalDao.getAll()
        all.filter { g ->
            g.title.lowercase().contains(q) ||
                g.description.lowercase().contains(q) ||
                g.steps.any { it.lowercase().contains(q) } ||
                g.category.lowercase().contains(q)
        }
            .sortedWith(compareBy({ it.status != GoalStatus.ACTIVE }, { -it.priority }))
            .map { it.toRecord() }
    }

    // ─── Progress Updates ─────────────────────────────────────────────────────

    suspend fun updateProgress(id: Long, progressPercent: Int) = withContext(Dispatchers.IO) {
        require(progressPercent in 0..100)
        val existing = goalDao.getById(id) ?: return@withContext
        val newStatus = if (progressPercent >= 100) GoalStatus.COMPLETED else existing.status
        goalDao.update(
            existing.copy(
                progressPercent = progressPercent,
                status = newStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
        Log.d(TAG, "Goal $id progress=$progressPercent% status=$newStatus")
    }

    suspend fun completeStep(goalId: Long, step: String) = withContext(Dispatchers.IO) {
        val existing = goalDao.getById(goalId) ?: return@withContext
        if (step in existing.completedSteps) return@withContext

        val newCompleted = existing.completedSteps + step
        val totalSteps = existing.steps.size.coerceAtLeast(1)
        val newProgress = ((newCompleted.size.toFloat() / totalSteps) * 100).toInt().coerceIn(0, 100)
        val newStatus = if (newProgress >= 100) GoalStatus.COMPLETED else existing.status

        goalDao.update(
            existing.copy(
                completedSteps = newCompleted,
                progressPercent = newProgress,
                status = newStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
        Log.d(TAG, "Goal $goalId step completed: '$step' ($newProgress%)")
    }

    suspend fun addStep(goalId: Long, step: String) = withContext(Dispatchers.IO) {
        val existing = goalDao.getById(goalId) ?: return@withContext
        goalDao.update(
            existing.copy(
                steps = existing.steps + step,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun addNextStep(goalId: Long, step: String) = addStep(goalId, step)

    // ─── Status Transitions ───────────────────────────────────────────────────

    suspend fun pauseGoal(goalId: Long, resumeContext: String = "") = withContext(Dispatchers.IO) {
        val existing = goalDao.getById(goalId) ?: return@withContext
        goalDao.update(
            existing.copy(
                status = GoalStatus.PAUSED,
                resumeContext = resumeContext,
                updatedAt = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "Goal $goalId paused with context: '$resumeContext'")
    }

    suspend fun resumeGoal(goalId: Long) = withContext(Dispatchers.IO) {
        val existing = goalDao.getById(goalId) ?: return@withContext
        goalDao.update(
            existing.copy(
                status = GoalStatus.ACTIVE,
                updatedAt = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "Goal $goalId resumed")
    }

    suspend fun completeGoal(goalId: Long) = withContext(Dispatchers.IO) {
        val existing = goalDao.getById(goalId) ?: return@withContext
        goalDao.update(
            existing.copy(
                status = GoalStatus.COMPLETED,
                progressPercent = 100,
                updatedAt = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "Goal $goalId completed")
    }

    suspend fun cancelGoal(goalId: Long) = withContext(Dispatchers.IO) {
        val existing = goalDao.getById(goalId) ?: return@withContext
        goalDao.update(
            existing.copy(
                status = GoalStatus.CANCELLED,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ─── Resume interrupted tasks ─────────────────────────────────────────────

    /**
     * Returns goals that were PAUSED in the last session, suitable for resuming.
     */
    suspend fun getInterruptedGoals(): List<GoalRecord> = withContext(Dispatchers.IO) {
        goalDao.getByStatus(GoalStatus.PAUSED)
            .sortedByDescending { it.updatedAt }
            .map { it.toRecord() }
    }

    /**
     * Build a session start context string that reminds the AI of interrupted tasks.
     */
    suspend fun buildResumeContext(): String = withContext(Dispatchers.IO) {
        val paused = goalDao.getByStatus(GoalStatus.PAUSED)
        val active = goalDao.getByStatus(GoalStatus.ACTIVE).take(5)

        if (paused.isEmpty() && active.isEmpty()) return@withContext ""

        val sb = StringBuilder()
        if (paused.isNotEmpty()) {
            sb.appendLine("=== INTERRUPTED GOALS (from last session) ===")
            paused.forEach { g ->
                sb.appendLine("• [PAUSED] ${g.title} (${g.progressPercent}% done)")
                if (g.resumeContext.isNotBlank()) sb.appendLine("  Context: ${g.resumeContext}")
                val nextStep = g.steps.firstOrNull { it !in g.completedSteps }
                if (nextStep != null) sb.appendLine("  Next step: $nextStep")
            }
        }
        if (active.isNotEmpty()) {
            sb.appendLine("=== ACTIVE GOALS ===")
            active.forEach { g ->
                sb.appendLine("• ${g.title} (${g.progressPercent}%)")
            }
        }
        sb.toString().trim()
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    suspend fun getGoalStats(): GoalStats = withContext(Dispatchers.IO) {
        val all = goalDao.getAll()
        GoalStats(
            total = all.size,
            active = all.count { it.status == GoalStatus.ACTIVE },
            paused = all.count { it.status == GoalStatus.PAUSED },
            completed = all.count { it.status == GoalStatus.COMPLETED },
            cancelled = all.count { it.status == GoalStatus.CANCELLED },
            avgProgress = all.map { it.progressPercent }.average().toFloat()
        )
    }

    private fun GoalEntity.toRecord() = GoalRecord(
        id = id,
        title = title,
        description = description,
        steps = steps,
        completedSteps = completedSteps,
        status = status,
        progressPercent = progressPercent,
        priority = priority,
        dueAtMs = dueAtMs,
        category = category,
        resumeContext = resumeContext,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

data class GoalRecord(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val steps: List<String> = emptyList(),
    val completedSteps: List<String> = emptyList(),
    val status: String = GoalStatus.ACTIVE,
    val progressPercent: Int = 0,
    val priority: Int = 2,
    val dueAtMs: Long? = null,
    val category: String = "general",
    val resumeContext: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class GoalStats(
    val total: Int,
    val active: Int,
    val paused: Int,
    val completed: Int,
    val cancelled: Int,
    val avgProgress: Float
)
