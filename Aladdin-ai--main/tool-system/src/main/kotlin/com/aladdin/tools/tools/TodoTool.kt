package com.aladdin.tools.tools

import com.aladdin.tools.db.dao.TodoDao
import com.aladdin.tools.db.entity.TaskPriority
import com.aladdin.tools.db.entity.TaskStatus
import com.aladdin.tools.db.entity.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * To-Do tool — Room SQLite with priority + due dates.
 *
 * Commands:
 *   add      — add a task
 *   done     — mark task complete
 *   delete   — delete a task
 *   update   — update title/priority/due
 *   list     — list tasks (optional: by list name)
 *   lists    — show all list names
 *   overdue  — show overdue tasks
 *   clear    — clear completed tasks
 *
 * Params: command, title, description, priority (LOW|NORMAL|HIGH|CRITICAL),
 *   list, due_at (epoch ms), due_in_days, task_id
 */
@Singleton
class TodoTool @Inject constructor(private val todoDao: TodoDao) : BaseTool {

    override val id = "todo"
    override val name = "To-Do"
    override val description = "Manage task lists with priority levels, due dates, and completion tracking"

    companion object {
        private val DUE_FMT = SimpleDateFormat("EEE, MMM d", Locale.US)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "add") {
            "done"    -> markDone(params)
            "delete"  -> deleteTask(params)
            "update"  -> updateTask(params)
            "lists"   -> showLists()
            "overdue" -> showOverdue()
            "clear"   -> clearCompleted(params)
            "list"    -> listTasks(params)
            else      -> addTask(params)
        }
    }

    private suspend fun addTask(params: Map<String, String>): ToolResult {
        val title = params["title"] ?: return ToolResult.error(id, "Missing title")
        val priority = parsePriority(params["priority"])
        val listName = params["list"] ?: "default"
        val dueAt = resolveDueTime(params)
        val entity = TodoEntity(
            title = title,
            description = params["description"] ?: "",
            priority = priority,
            listName = listName,
            dueAt = dueAt
        )
        val taskId = todoDao.insert(entity)
        val dueStr = dueAt?.let { " (due ${DUE_FMT.format(Date(it))})" } ?: ""
        return ToolResult.success(id, "✅ Task added: '$title' [${priority.name}]$dueStr (ID: $taskId)")
    }

    private suspend fun markDone(params: Map<String, String>): ToolResult {
        val taskId = params["task_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing task_id")
        val task = todoDao.getById(taskId) ?: return ToolResult.error(id, "Task $taskId not found")
        todoDao.markDone(taskId)
        return ToolResult.success(id, "✔ Task '${task.title}' marked complete!")
    }

    private suspend fun deleteTask(params: Map<String, String>): ToolResult {
        val taskId = params["task_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing task_id")
        todoDao.deleteById(taskId)
        return ToolResult.success(id, "🗑 Task $taskId deleted")
    }

    private suspend fun updateTask(params: Map<String, String>): ToolResult {
        val taskId = params["task_id"]?.toLongOrNull() ?: return ToolResult.error(id, "Missing task_id")
        val existing = todoDao.getById(taskId) ?: return ToolResult.error(id, "Task $taskId not found")
        val updated = existing.copy(
            title = params["title"] ?: existing.title,
            description = params["description"] ?: existing.description,
            priority = params["priority"]?.let { parsePriority(it) } ?: existing.priority,
            listName = params["list"] ?: existing.listName,
            dueAt = resolveDueTime(params) ?: existing.dueAt,
            updatedAt = System.currentTimeMillis()
        )
        todoDao.update(updated)
        return ToolResult.success(id, "✅ Task '${updated.title}' updated")
    }

    private suspend fun listTasks(params: Map<String, String>): ToolResult {
        val listName = params["list"]
        val tasks = if (listName != null) todoDao.getByList(listName) else todoDao.getPending()
        if (tasks.isEmpty()) return ToolResult.success(id, "No tasks${listName?.let { " in '$it'" } ?: ""}.")

        val headerName = listName ?: "Pending"
        val sb = StringBuilder("📋 $headerName Tasks (${tasks.size}):\n\n")
        tasks.forEach { t ->
            val priority = when (t.priority) {
                TaskPriority.CRITICAL -> "🔴"
                TaskPriority.HIGH     -> "🟠"
                TaskPriority.NORMAL   -> "🟡"
                TaskPriority.LOW      -> "⚪"
            }
            val due = t.dueAt?.let { " · due ${DUE_FMT.format(Date(it))}" } ?: ""
            val status = if (t.status == TaskStatus.DONE) "~~" else ""
            sb.appendLine("$priority [${t.id}] $status${t.title}$status$due")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private suspend fun showLists(): ToolResult {
        val lists = todoDao.getLists()
        return if (lists.isEmpty()) ToolResult.success(id, "No task lists yet.")
        else ToolResult.success(id, "📋 Lists: ${lists.joinToString(", ")}")
    }

    private suspend fun showOverdue(): ToolResult {
        val overdue = todoDao.getOverdue()
        if (overdue.isEmpty()) return ToolResult.success(id, "🎉 No overdue tasks!")
        val sb = StringBuilder("⚠ Overdue Tasks (${overdue.size}):\n\n")
        overdue.forEach { t ->
            val due = t.dueAt?.let { " — was due ${DUE_FMT.format(Date(it))}" } ?: ""
            sb.appendLine("[${t.id}] ${t.title}$due")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private suspend fun clearCompleted(params: Map<String, String>): ToolResult {
        val daysOld = params["older_than_days"]?.toIntOrNull() ?: 7
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysOld.toLong())
        val count = todoDao.cleanupCompleted(before)
        return ToolResult.success(id, "🗑 Cleared $count completed task(s) older than $daysOld days")
    }

    private fun parsePriority(s: String?) = when (s?.uppercase()) {
        "CRITICAL" -> TaskPriority.CRITICAL
        "HIGH"     -> TaskPriority.HIGH
        "LOW"      -> TaskPriority.LOW
        else       -> TaskPriority.NORMAL
    }

    private fun resolveDueTime(params: Map<String, String>): Long? {
        params["due_at"]?.toLongOrNull()?.let { return it }
        params["due_in_days"]?.toLongOrNull()?.let {
            return System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it)
        }
        return null
    }
}
