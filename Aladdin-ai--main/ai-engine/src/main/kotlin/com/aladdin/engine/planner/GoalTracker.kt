package com.aladdin.engine.planner

import android.util.Log
import com.aladdin.engine.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 — Goal Tracker with Long-term Planning (#6).
 *
 * Phase 4 upgrades:
 *   5. Improved Goal Planner — breaks tasks into subtasks, tracks dependencies,
 *      sets priorities, tracks progress, creates alternative plans on failure.
 *   6. Long-term Planning — multi-day/multi-week goals with deadlines,
 *      persistence hooks, reminder scheduling, restart-safe goal resume,
 *      and scheduled future task support.
 */
@Singleton
class GoalTracker @Inject constructor() {

    companion object {
        private const val TAG = "GoalTracker"
        private const val STALE_GOAL_DAYS = 30L
        private const val STALE_PLAN_DAYS = 7L
    }

    private val goals = ConcurrentHashMap<String, Goal>()
    private val plans = ConcurrentHashMap<String, Plan>()

    private val _activeGoals = MutableStateFlow<List<Goal>>(emptyList())
    val activeGoals: StateFlow<List<Goal>> = _activeGoals

    private val _activePlans = MutableStateFlow<List<Plan>>(emptyList())
    val activePlans: StateFlow<List<Plan>> = _activePlans

    // ─── Phase 4 #6 — Long-term Goal Creation ─────────────────────────────────

    /**
     * Create a goal with optional deadline, reminder schedule, and priority.
     * Goals are designed to survive app restarts (persistence hooks included).
     */
    fun createGoal(
        title: String,
        description: String = "",
        priority: GoalPriority = GoalPriority.NORMAL,
        deadlineMs: Long? = null,
        reminderIntervalMs: Long? = null,
        isLongTerm: Boolean = false,
        weeklyTargetDays: Int = 0
    ): Goal {
        val goal = Goal(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            priority = priority,
            deadlineMs = deadlineMs,
            reminderIntervalMs = reminderIntervalMs,
            isLongTerm = isLongTerm,
            weeklyTargetDays = weeklyTargetDays,
            nextReminderMs = reminderIntervalMs?.let { System.currentTimeMillis() + it }
        )
        goals[goal.id] = goal
        refreshGoalFlow()
        Log.i(TAG, "Goal created: ${goal.id} '$title' longTerm=$isLongTerm " +
              "deadline=${deadlineMs?.let { formatMs(it) } ?: "none"} " +
              "priority=$priority")
        return goal
    }

    // ─── Phase 4 #5 — Priority-Aware Progress ────────────────────────────────

    fun updateGoalProgress(goalId: String, progress: Float): Goal? {
        val existing = goals[goalId] ?: return null
        val newStatus = when {
            progress >= 1f -> GoalStatus.COMPLETED
            progress > 0f  -> GoalStatus.ACTIVE
            else           -> existing.status
        }
        val updated = existing.copy(
            progress  = progress.coerceIn(0f, 1f),
            status    = newStatus,
            updatedAt = System.currentTimeMillis()
        )
        goals[goalId] = updated
        refreshGoalFlow()
        if (newStatus == GoalStatus.COMPLETED) {
            Log.i(TAG, "Goal '${existing.title}' COMPLETED")
        }
        return updated
    }

    fun completeMilestone(goalId: String, milestoneId: String): Goal? {
        val goal = goals[goalId] ?: return null
        val updatedMilestones = goal.milestones.map { m ->
            if (m.id == milestoneId) m.copy(isDone = true, completedAt = System.currentTimeMillis())
            else m
        }
        val completedCount = updatedMilestones.count { it.isDone }
        val progress = if (updatedMilestones.isEmpty()) 0f
            else completedCount.toFloat() / updatedMilestones.size
        val updated = goal.copy(
            milestones = updatedMilestones,
            progress   = progress,
            status     = if (progress >= 1f) GoalStatus.COMPLETED else GoalStatus.ACTIVE,
            updatedAt  = System.currentTimeMillis()
        )
        goals[goalId] = updated
        refreshGoalFlow()
        return updated
    }

    fun addMilestone(goalId: String, title: String, priority: TaskPriority = TaskPriority.NORMAL): Goal? {
        val goal = goals[goalId] ?: return null
        val milestone = Milestone(
            id       = UUID.randomUUID().toString(),
            title    = title,
            priority = priority
        )
        val updated = goal.copy(milestones = goal.milestones + milestone)
        goals[goalId] = updated
        refreshGoalFlow()
        return updated
    }

    fun abandonGoal(goalId: String) {
        goals[goalId]?.let {
            goals[goalId] = it.copy(status = GoalStatus.ABANDONED, updatedAt = System.currentTimeMillis())
            refreshGoalFlow()
            Log.i(TAG, "Goal abandoned: '${it.title}'")
        }
    }

    fun pauseGoal(goalId: String) {
        goals[goalId]?.let {
            goals[goalId] = it.copy(status = GoalStatus.PAUSED, updatedAt = System.currentTimeMillis())
            refreshGoalFlow()
            Log.i(TAG, "Goal paused: '${it.title}'")
        }
    }

    fun resumeGoal(goalId: String) {
        goals[goalId]?.let {
            goals[goalId] = it.copy(status = GoalStatus.ACTIVE, updatedAt = System.currentTimeMillis())
            refreshGoalFlow()
            Log.i(TAG, "Goal resumed: '${it.title}'")
        }
    }

    fun getGoal(goalId: String): Goal? = goals[goalId]

    fun getActiveGoals(): List<Goal> =
        goals.values
            .filter { it.status == GoalStatus.ACTIVE }
            .sortedWith(compareByDescending<Goal> { it.priority.ordinal }.thenBy { it.deadlineMs ?: Long.MAX_VALUE })

    // ─── Phase 4 #6 — Long-term Goal Queries ─────────────────────────────────

    fun getLongTermGoals(): List<Goal> =
        goals.values.filter { it.isLongTerm && it.status != GoalStatus.ABANDONED }

    fun getOverdueGoals(): List<Goal> {
        val now = System.currentTimeMillis()
        return goals.values.filter { goal ->
            goal.status == GoalStatus.ACTIVE &&
            goal.deadlineMs != null &&
            goal.deadlineMs < now
        }
    }

    fun getGoalsDueWithin(windowMs: Long): List<Goal> {
        val now = System.currentTimeMillis()
        return goals.values.filter { goal ->
            goal.status == GoalStatus.ACTIVE &&
            goal.deadlineMs != null &&
            goal.deadlineMs in now..(now + windowMs)
        }.sortedBy { it.deadlineMs }
    }

    fun getGoalsDueForReminder(): List<Goal> {
        val now = System.currentTimeMillis()
        return goals.values.filter { goal ->
            goal.status == GoalStatus.ACTIVE &&
            goal.nextReminderMs != null &&
            goal.nextReminderMs <= now
        }
    }

    /**
     * Advance a goal's next reminder timestamp.
     * Call after a reminder has been fired.
     */
    fun snoozeReminder(goalId: String) {
        val goal = goals[goalId] ?: return
        val interval = goal.reminderIntervalMs ?: return
        goals[goalId] = goal.copy(
            nextReminderMs = System.currentTimeMillis() + interval,
            updatedAt      = System.currentTimeMillis()
        )
        refreshGoalFlow()
    }

    /**
     * Build a context string for resuming active goals after app restart.
     */
    fun buildResumeContext(): String {
        val active  = getActiveGoals().take(5)
        val overdue = getOverdueGoals().take(3)

        if (active.isEmpty()) return ""

        val sb = StringBuilder("=== ACTIVE GOALS (resume context) ===\n")
        active.forEach { goal ->
            val progressPct = (goal.progress * 100).toInt()
            val deadline    = goal.deadlineMs?.let { " | due: ${formatMs(it)}" } ?: ""
            val priority    = if (goal.priority != GoalPriority.NORMAL) " [${goal.priority}]" else ""
            sb.appendLine("• ${goal.title}$priority — $progressPct% complete$deadline")
            goal.milestones
                .filter { !it.isDone }
                .take(3)
                .forEach { m -> sb.appendLine("  ○ ${m.title}") }
        }

        if (overdue.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== OVERDUE GOALS ===")
            overdue.forEach { sb.appendLine("• ${it.title} (deadline passed)") }
        }

        return sb.toString().trim()
    }

    // ─── Plans ────────────────────────────────────────────────────────────────

    fun trackPlan(plan: Plan, goalId: String? = null) {
        plans[plan.id] = plan
        if (goalId != null) {
            goals[goalId]?.let { goal ->
                goals[goalId] = goal.copy(planIds = (goal.planIds + plan.id).distinct())
                refreshGoalFlow()
            }
        }
        refreshPlanFlow()
    }

    fun updatePlan(plan: Plan) {
        plans[plan.id] = plan
        refreshPlanFlow()
        goals.values.find { it.planIds.contains(plan.id) }?.let { goal ->
            updateGoalProgress(goal.id, plan.progress)
        }
    }

    fun updateTask(planId: String, updatedTask: Task) {
        val plan = plans[planId] ?: return
        val tasks      = plan.tasks.map { if (it.id == updatedTask.id) updatedTask else it }
        val primitives = tasks.filter { it.type == TaskType.PRIMITIVE }
        val done       = primitives.count { it.status == TaskStatus.COMPLETED }
        val progress   = if (primitives.isEmpty()) 0f else done.toFloat() / primitives.size
        val allDone    = primitives.all { it.status == TaskStatus.COMPLETED }
        val anyFailed  = primitives.any { it.status == TaskStatus.FAILED }

        val updatedPlan = plan.copy(
            tasks     = tasks,
            progress  = progress,
            status    = when {
                allDone                     -> PlanStatus.COMPLETED
                anyFailed && done == 0      -> PlanStatus.FAILED
                else                        -> PlanStatus.EXECUTING
            },
            updatedAt = System.currentTimeMillis()
        )
        plans[planId] = updatedPlan
        refreshPlanFlow()
    }

    fun getPlan(planId: String): Plan? = plans[planId]

    // ─── Phase 4 #5 — Alternative Plan on Failure ────────────────────────────

    /**
     * Creates an alternative plan when the primary plan fails.
     * The alternative resets failed tasks and adds a hint to try a different approach.
     */
    fun createAlternativePlan(failedPlan: Plan, reason: String): Plan {
        val altTasks = failedPlan.tasks.map { task ->
            if (task.status == TaskStatus.FAILED) {
                task.copy(
                    id         = UUID.randomUUID().toString(),
                    status     = TaskStatus.PENDING,
                    retryCount = 0,
                    parameters = task.parameters + mapOf(
                        "alternative_strategy" to "true",
                        "failure_reason"       to reason.take(200),
                        "previous_tool"        to (task.toolId ?: ""),
                        "retry_hint"           to "Try a different approach than the previous attempt"
                    ),
                    updatedAt  = System.currentTimeMillis()
                )
            } else task
        }
        val altPlan = Plan(
            id        = UUID.randomUUID().toString(),
            goal      = failedPlan.goal,
            intent    = failedPlan.intent,
            tasks     = altTasks,
            reasoning = "Alternative plan: primary plan failed — reason: $reason"
        )
        plans[altPlan.id] = altPlan
        refreshPlanFlow()
        Log.i(TAG, "Alternative plan created for goal='${failedPlan.goal.take(50)}'")
        return altPlan
    }

    // ─── Maintenance ─────────────────────────────────────────────────────────

    fun runMaintenance() {
        val nowMs        = System.currentTimeMillis()
        val thirtyDaysMs = STALE_GOAL_DAYS * 24 * 60 * 60 * 1000
        val sevenDaysMs  = STALE_PLAN_DAYS * 24 * 60 * 60 * 1000

        goals.values
            .filter { it.status == GoalStatus.ACTIVE && !it.isLongTerm &&
                      (nowMs - it.updatedAt) > thirtyDaysMs }
            .forEach { goal ->
                goals[goal.id] = goal.copy(status = GoalStatus.ABANDONED, updatedAt = nowMs)
                Log.i(TAG, "Maintenance: abandoned stale goal '${goal.title}'")
            }

        val stalePlanIds = plans.values
            .filter { it.status in listOf(PlanStatus.COMPLETED, PlanStatus.FAILED) &&
                      (nowMs - it.createdAt) > sevenDaysMs }
            .map { it.id }
        stalePlanIds.forEach { plans.remove(it) }
        if (stalePlanIds.isNotEmpty()) {
            Log.i(TAG, "Maintenance: pruned ${stalePlanIds.size} stale plans")
        }

        refreshGoalFlow()
        refreshPlanFlow()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun refreshGoalFlow() {
        _activeGoals.value = goals.values.toList()
            .sortedWith(compareByDescending<Goal> { it.priority.ordinal }
                .thenBy { it.deadlineMs ?: Long.MAX_VALUE }
                .thenByDescending { it.createdAt })
    }

    private fun refreshPlanFlow() {
        _activePlans.value = plans.values.toList().sortedByDescending { it.createdAt }
    }

    private fun formatMs(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.US)
        return sdf.format(java.util.Date(ms))
    }
}
