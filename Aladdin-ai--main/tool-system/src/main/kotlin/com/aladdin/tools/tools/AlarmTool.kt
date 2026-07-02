package com.aladdin.tools.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aladdin.tools.db.dao.AlarmDao
import com.aladdin.tools.db.entity.AlarmEntity
import com.aladdin.tools.receiver.AlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alarm tool — Android AlarmManager.
 *
 * Commands:
 *   set      — schedule a one-shot or recurring alarm
 *   delete   — cancel an alarm by ID
 *   snooze   — snooze the last triggered alarm
 *   list     — list all active alarms
 *   disable  — disable without deleting
 *   enable   — re-enable a disabled alarm
 *
 * Params:
 *   command, label, trigger_at (epoch ms), in_minutes, in_hours,
 *   recurring, repeat_days (MON,WED,FRI), snooze_minutes, alarm_id
 */
@Singleton
class AlarmTool @Inject constructor(
    private val context: Context,
    private val alarmDao: AlarmDao
) : BaseTool {

    override val id = "alarm"
    override val name = "Alarm"
    override val description = "Set, delete, snooze, and list alarms using Android AlarmManager"

    companion object {
        private const val TAG = "AlarmTool"
        private val FMT = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.US)
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "set") {
            "delete"  -> deleteAlarm(params)
            "snooze"  -> snoozeAlarm(params)
            "list"    -> listAlarms()
            "disable" -> toggleAlarm(params, false)
            "enable"  -> toggleAlarm(params, true)
            else      -> setAlarm(params)
        }
    }

    // ─── Set ──────────────────────────────────────────────────────────────────

    private suspend fun setAlarm(params: Map<String, String>): ToolResult {
        val label = params["label"] ?: params["title"] ?: "Alarm"
        val triggerAt = resolveTriggerTime(params)
            ?: return ToolResult.error(id, "Missing time: provide trigger_at (epoch ms), in_minutes, or in_hours")

        val recurring = params["recurring"]?.equals("true", ignoreCase = true) ?: false
        val repeatDays = params["repeat_days"] ?: ""
        val snoozeMin = params["snooze_minutes"]?.toIntOrNull() ?: 5

        val entity = AlarmEntity(
            label = label,
            triggerAt = triggerAt,
            isRecurring = recurring || repeatDays.isNotBlank(),
            repeatDays = repeatDays,
            snoozeMinutes = snoozeMin
        )
        val alarmId = alarmDao.insert(entity)
        scheduleAndroidAlarm(alarmId, label, triggerAt)

        val timeStr = FMT.format(Date(triggerAt))
        val recurStr = if (recurring || repeatDays.isNotBlank()) " (recurring: $repeatDays)" else ""
        Log.i(TAG, "Alarm set: id=$alarmId '$label' at $timeStr$recurStr")
        return ToolResult.success(id, "⏰ Alarm set: '$label' at $timeStr$recurStr (ID: $alarmId)")
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    private suspend fun deleteAlarm(params: Map<String, String>): ToolResult {
        val alarmId = params["alarm_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing alarm_id")
        val alarm = alarmDao.getById(alarmId) ?: return ToolResult.error(id, "Alarm $alarmId not found")
        cancelAndroidAlarm(alarmId)
        alarmDao.delete(alarm)
        return ToolResult.success(id, "🗑 Alarm '$${alarm.label}' (ID: $alarmId) deleted")
    }

    // ─── Snooze ───────────────────────────────────────────────────────────────

    private suspend fun snoozeAlarm(params: Map<String, String>): ToolResult {
        val alarmId = params["alarm_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing alarm_id")
        val alarm = alarmDao.getById(alarmId) ?: return ToolResult.error(id, "Alarm $alarmId not found")
        val snoozeMin = params["snooze_minutes"]?.toIntOrNull() ?: alarm.snoozeMinutes
        val newTrigger = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(snoozeMin.toLong())

        val updated = alarm.copy(triggerAt = newTrigger)
        alarmDao.update(updated)
        scheduleAndroidAlarm(alarmId, alarm.label, newTrigger)

        val timeStr = FMT.format(Date(newTrigger))
        return ToolResult.success(id, "😴 Alarm '${alarm.label}' snoozed until $timeStr")
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    private suspend fun listAlarms(): ToolResult {
        val alarms = alarmDao.getActive()
        if (alarms.isEmpty()) return ToolResult.success(id, "No active alarms.")
        val sb = StringBuilder("⏰ Active Alarms:\n\n")
        alarms.forEach { a ->
            val timeStr = FMT.format(Date(a.triggerAt))
            val recurStr = if (a.isRecurring) " 🔁${if (a.repeatDays.isNotBlank()) " ${a.repeatDays}" else ""}" else ""
            sb.appendLine("[${a.id}] ${a.label} — $timeStr$recurStr")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    // ─── Enable / Disable ─────────────────────────────────────────────────────

    private suspend fun toggleAlarm(params: Map<String, String>, active: Boolean): ToolResult {
        val alarmId = params["alarm_id"]?.toLongOrNull()
            ?: return ToolResult.error(id, "Missing alarm_id")
        alarmDao.setActive(alarmId, active)
        val alarm = alarmDao.getById(alarmId) ?: return ToolResult.error(id, "Alarm $alarmId not found")
        if (active) scheduleAndroidAlarm(alarmId, alarm.label, alarm.triggerAt)
        else cancelAndroidAlarm(alarmId)
        val action = if (active) "enabled" else "disabled"
        return ToolResult.success(id, "Alarm '${alarm.label}' $action")
    }

    // ─── AlarmManager integration ─────────────────────────────────────────────

    private fun scheduleAndroidAlarm(alarmId: Long, label: String, triggerAt: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.aladdin.tools.ALARM_TRIGGER"
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_ALARM_LABEL, label)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.d(TAG, "Android alarm scheduled: id=$alarmId at $triggerAt")
        } catch (e: SecurityException) {
            // Fall back to inexact if exact alarm permission not granted
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.w(TAG, "Using inexact alarm (no SCHEDULE_EXACT_ALARM permission)")
        }
    }

    private fun cancelAndroidAlarm(alarmId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, alarmId.toInt(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { alarmManager.cancel(it) }
    }

    // ─── Time helpers ─────────────────────────────────────────────────────────

    private fun resolveTriggerTime(params: Map<String, String>): Long? {
        params["trigger_at"]?.toLongOrNull()?.let { return it }
        params["in_minutes"]?.toLongOrNull()?.let { return System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(it) }
        params["in_hours"]?.toLongOrNull()?.let { return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(it) }
        params["in_seconds"]?.toLongOrNull()?.let { return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(it) }
        return null
    }
}
