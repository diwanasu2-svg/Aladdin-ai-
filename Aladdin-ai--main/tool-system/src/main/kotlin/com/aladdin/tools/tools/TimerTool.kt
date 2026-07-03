package com.aladdin.tools.tools

import android.content.Context
import android.os.CountDownTimer
import android.util.Log
import com.aladdin.tools.db.dao.TimerDao
import com.aladdin.tools.db.entity.TimerEntity
import com.aladdin.tools.db.entity.TimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Timer tool — CountDownTimer with pause/resume.
 *
 * Commands:
 *   start  — create and start a countdown timer
 *   pause  — pause a running timer
 *   resume — resume a paused timer
 *   reset  — reset timer to original duration
 *   stop   — stop and remove timer
 *   list   — list all timers
 *   status — status of a specific timer
 *
 * Params:
 *   command, label, duration_seconds, duration_minutes, duration_hours, timer_id
 */
@Singleton
class TimerTool @Inject constructor(
    private val context: Context,
    private val timerDao: TimerDao
) : BaseTool {

    override val id = "timer"
    override val name = "Timer"
    override val description = "Start, pause, resume, and reset countdown timers with completion notifications"

    companion object {
        private const val TAG = "TimerTool"
    }

    // Active CountDownTimer instances keyed by timer DB id
    private val activeTimers = ConcurrentHashMap<Long, CountDownTimer>()

    // Real-time remaining ms per timer id
    private val _timerProgress = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val timerProgress: Flow<Map<Long, Long>> = _timerProgress

    // ─── Entry point ──────────────────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "start") {
            "pause"  -> pauseTimer(params)
            "resume" -> resumeTimer(params)
            "reset"  -> resetTimer(params)
            "stop"   -> stopTimer(params)
            "list"   -> listTimers()
            "status" -> timerStatus(params)
            else     -> startTimer(params)
        }
    }

    // ─── Start ────────────────────────────────────────────────────────────────

    private suspend fun startTimer(params: Map<String, String>): ToolResult {
        val label = params["label"] ?: "Timer"
        val durationMs = resolveDuration(params)
            ?: return ToolResult.error(id, "Missing duration: provide duration_seconds, duration_minutes, or duration_hours")

        val entity = TimerEntity(
            label = label,
            durationMs = durationMs,
            remainingMs = durationMs,
            state = TimerState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        val timerId = timerDao.insert(entity)
        startCountdown(timerId, label, durationMs)

        val friendly = formatDuration(durationMs)
        Log.i(TAG, "Timer started: id=$timerId '$label' for $friendly")
        return ToolResult.success(id, "⏱ Timer '$label' started for $friendly (ID: $timerId)")
    }

    // ─── Pause ────────────────────────────────────────────────────────────────

    private suspend fun pauseTimer(params: Map<String, String>): ToolResult {
        val timerId = params["timer_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing timer_id")
        val timer = timerDao.getById(timerId) ?: return ToolResult.error(id, "Timer $timerId not found")
        if (timer.state != TimerState.RUNNING) return ToolResult.error(id, "Timer $timerId is not running")

        // Calculate remaining time
        val elapsed = timer.startedAt?.let { System.currentTimeMillis() - it } ?: 0
        val remaining = (timer.remainingMs - elapsed).coerceAtLeast(0)

        activeTimers.remove(timerId)?.cancel()
        timerDao.updateState(timerId, TimerState.PAUSED.name, remaining)

        return ToolResult.success(id, "⏸ Timer '${timer.label}' paused (${formatDuration(remaining)} remaining)")
    }

    // ─── Resume ───────────────────────────────────────────────────────────────

    private suspend fun resumeTimer(params: Map<String, String>): ToolResult {
        val timerId = params["timer_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing timer_id")
        val timer = timerDao.getById(timerId) ?: return ToolResult.error(id, "Timer $timerId not found")
        if (timer.state != TimerState.PAUSED) return ToolResult.error(id, "Timer $timerId is not paused")

        val remaining = timer.remainingMs
        val entity = timer.copy(state = TimerState.RUNNING, startedAt = System.currentTimeMillis(), remainingMs = remaining)
        timerDao.update(entity)
        startCountdown(timerId, timer.label, remaining)

        return ToolResult.success(id, "▶ Timer '${timer.label}' resumed (${formatDuration(remaining)} remaining)")
    }

    // ─── Reset ────────────────────────────────────────────────────────────────

    private suspend fun resetTimer(params: Map<String, String>): ToolResult {
        val timerId = params["timer_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing timer_id")
        val timer = timerDao.getById(timerId) ?: return ToolResult.error(id, "Timer $timerId not found")

        activeTimers.remove(timerId)?.cancel()
        val entity = timer.copy(state = TimerState.IDLE, remainingMs = timer.durationMs, startedAt = null, pausedAt = null)
        timerDao.update(entity)

        return ToolResult.success(id, "🔄 Timer '${timer.label}' reset to ${formatDuration(timer.durationMs)}")
    }

    // ─── Stop / Delete ────────────────────────────────────────────────────────

    private suspend fun stopTimer(params: Map<String, String>): ToolResult {
        val timerId = params["timer_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing timer_id")
        val timer = timerDao.getById(timerId) ?: return ToolResult.error(id, "Timer $timerId not found")
        activeTimers.remove(timerId)?.cancel()
        timerDao.delete(timer)
        return ToolResult.success(id, "⏹ Timer '${timer.label}' stopped")
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    private suspend fun listTimers(): ToolResult {
        val running = timerDao.getRunning()
        if (running.isEmpty()) return ToolResult.success(id, "No active timers.")
        val sb = StringBuilder("⏱ Active Timers:\n\n")
        running.forEach { t ->
            val elapsed = t.startedAt?.let { System.currentTimeMillis() - it } ?: 0
            val remaining = (t.remainingMs - elapsed).coerceAtLeast(0)
            sb.appendLine("[${t.id}] ${t.label} — ${formatDuration(remaining)} remaining")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    private suspend fun timerStatus(params: Map<String, String>): ToolResult {
        val timerId = params["timer_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing timer_id")
        val timer = timerDao.getById(timerId) ?: return ToolResult.error(id, "Timer $timerId not found")
        val elapsed = if (timer.state == TimerState.RUNNING) timer.startedAt?.let { System.currentTimeMillis() - it } ?: 0L else 0L
        val remaining = (timer.remainingMs - elapsed).coerceAtLeast(0)
        return ToolResult.success(id, "[${timer.id}] ${timer.label}: ${timer.state} — ${formatDuration(remaining)} remaining")
    }

    // ─── CountDownTimer management ────────────────────────────────────────────

    private fun startCountdown(timerId: Long, label: String, durationMs: Long) {
        activeTimers.remove(timerId)?.cancel()
        val timer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(remainingMs: Long) {
                _timerProgress.value = _timerProgress.value + (timerId to remainingMs)
            }
            override fun onFinish() {
                Log.i(TAG, "Timer finished: id=$timerId '$label'")
                _timerProgress.value = _timerProgress.value - timerId
                activeTimers.remove(timerId)
                sendTimerNotification(context, timerId, label)
                // Update DB state
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    timerDao.updateState(timerId, TimerState.FINISHED.name, 0L)
                }
            }
        }.start()
        activeTimers[timerId] = timer
    }

    private fun sendTimerNotification(ctx: Context, timerId: Long, label: String) {
        try {
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "timer_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "Timers", android.app.NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(channel)
            }
            val notification = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                .setContentTitle("⏱ Timer Complete!")
                .setContentText("'$label' has finished")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(timerId.toInt(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "Timer notification failed: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveDuration(params: Map<String, String>): Long? {
        params["duration_ms"]?.toLongOrNull()?.let { return it }
        params["duration_seconds"]?.toLongOrNull()?.let { return it * 1_000 }
        params["duration_minutes"]?.toLongOrNull()?.let { return it * 60_000 }
        params["duration_hours"]?.toLongOrNull()?.let { return it * 3_600_000 }
        return null
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0  -> "${h}h ${m}m ${s}s"
            m > 0  -> "${m}m ${s}s"
            else   -> "${s}s"
        }
    }
}

// Note: using kotlinx.coroutines.GlobalScope here — acceptable for a fire-and-forget
// DB update on timer completion; consider structured concurrency in a future refactor.
