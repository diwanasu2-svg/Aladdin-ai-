package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 10 — Notification Control Tool
 * Read, dismiss, reply to, filter, and prioritize notifications via Android NotificationListenerService.
 */
@Singleton
class NotificationControlTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "notification_control"

    override val name = "notification_control"
    override val description = "Read, dismiss, reply to, filter and prioritize Android notifications"

    companion object {
        // Shared reference populated by the NotificationListenerService
        var activeNotifications: Array<StatusBarNotification>? = null
        var notificationListenerService: android.service.notification.NotificationListenerService? = null
    }

    // ── Get all active notifications ──────────────────────────────────────
    suspend fun getNotifications(appFilter: String? = null, limit: Int = 20): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val notifs = activeNotifications ?: return@withContext ToolResult.error(id, "NotificationListenerService not connected — grant Notification Access permission")
                val result = notifs
                    .filter { sbn ->
                        appFilter == null || sbn.packageName.contains(appFilter, ignoreCase = true)
                    }
                    .take(limit)
                    .map { sbn ->
                        val extras = sbn.notification.extras
                        val appName = try {
                            context.packageManager.getApplicationLabel(
                                context.packageManager.getApplicationInfo(sbn.packageName, 0)
                            ).toString()
                        } catch (e: PackageManager.NameNotFoundException) { sbn.packageName }
                        JSONObject().apply {
                            put("key", sbn.key)
                            put("package", sbn.packageName)
                            put("app_name", appName)
                            put("title", extras?.getCharSequence("android.title")?.toString() ?: "")
                            put("text", extras?.getCharSequence("android.text")?.toString() ?: "")
                            put("posted_at", sbn.postTime)
                            put("ongoing", sbn.isOngoing)
                        }
                    }
                ToolResult.success(id, JSONObject().apply {
                    put("notifications", result.map { it.toString() })
                    put("count", result.size)
                }.toString())
            } catch (e: Exception) { ToolResult.error(id, "Get notifications error: ${e.message}") }
        }

    // ── Dismiss a notification ─────────────────────────────────────────────
    fun dismissNotification(key: String): ToolResult {
        return try {
            val listener = notificationListenerService
                ?: return ToolResult.error(id, "NotificationListenerService not connected")
            if (key == "all") {
                listener.cancelAllNotifications()
            } else {
                listener.cancelNotification(key)
            }
            ToolResult.success(id, JSONObject().put("dismissed", key).toString())
        } catch (e: Exception) { ToolResult.error(id, "Dismiss error: ${e.message}") }
    }

    // ── Reply to a notification inline ────────────────────────────────────
    fun replyToNotification(key: String, replyText: String): ToolResult {
        return try {
            val notifs = activeNotifications
                ?: return ToolResult.error(id, "NotificationListenerService not running")
            val sbn = notifs.firstOrNull { it.key == key }
                ?: return ToolResult.error(id, "Notification not found: $key")
            val actions = sbn.notification.actions ?: return ToolResult.error(id, "No actions available")
            val replyAction = actions.firstOrNull { action ->
                action.remoteInputs != null && action.remoteInputs.isNotEmpty()
            } ?: return ToolResult.error(id, "No reply action available")

            val remoteInput = android.app.RemoteInput.Builder(
                replyAction.remoteInputs[0].resultKey
            ).build()
            val bundle = android.os.Bundle().apply {
                putCharSequence(replyAction.remoteInputs[0].resultKey, replyText)
            }
            val intent = android.content.Intent()
            android.app.RemoteInput.addResultsToIntent(
                replyAction.remoteInputs, intent, bundle
            )
            replyAction.actionIntent.send(context, 0, intent)
            ToolResult.success(id, JSONObject().apply {
                put("replied_to", key); put("text", replyText)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Reply error: ${e.message}") }
    }

    // ── Get notification summary grouped by app ───────────────────────────
    suspend fun getSummary(): ToolResult = withContext(Dispatchers.IO) {
        val notifs = activeNotifications ?: return@withContext ToolResult.error(id, "Service not connected")
        val byApp = notifs.groupBy { it.packageName }
        val summary = byApp.mapValues { (_, v) -> v.size }
        return@withContext ToolResult.success(id, JSONObject().apply {
            put("total", notifs.size)
            put("by_app", JSONObject(summary as Map<*, *>))
            put("apps", summary.keys.toList().toString())
        }.toString())
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "list")) {
            "list" -> getNotifications(
                (params["app"] ?: "").ifEmpty { null },
                (params["limit"]?.toIntOrNull() ?: 20)
            )
            "dismiss" -> dismissNotification((params["key"] ?: return ToolResult.error(id, "Missing required parameter: " + "key")))
            "reply" -> replyToNotification((params["key"] ?: return ToolResult.error(id, "Missing required parameter: " + "key")), (params["reply"] ?: return ToolResult.error(id, "Missing required parameter: " + "reply")))
            "summary" -> getSummary()
            else -> ToolResult.error(id, "Unknown notification action: $action")
        }
    }
}
