package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 10 — App Automation Tool
 * Launch/close apps, navigate within apps, automate multi-step workflows, monitor app state.
 */
@Singleton
class AppAutomationTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "app_automation"

    override val name = "app_automation"
    override val description = "Launch/close apps, navigate UI, automate workflows, monitor running apps"

    companion object {
        var accessibilityService: AccessibilityService? = null
    }

    // ── Launch app by package name ────────────────────────────────────────
    fun launchApp(packageName: String): ToolResult {
        return try {
            val resolvedPkg = resolvePackage(packageName)
            val intent = context.packageManager.getLaunchIntentForPackage(resolvedPkg)
                ?: return ToolResult.error(id, "App not found: $resolvedPkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult.success(id, JSONObject().apply {
                put("launched", resolvedPkg); put("status", "started")
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Launch error: ${e.message}") }
    }

    // ── Close app ─────────────────────────────────────────────────────────
    fun closeApp(packageName: String): ToolResult {
        return try {
            val svc = accessibilityService
                ?: return ToolResult.error(id, "AccessibilityService not running")
            // Press back / use forceStopPackage (requires FORCE_STOP_PACKAGES permission)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ToolResult.success(id, JSONObject().apply { put("close_initiated", packageName) }.toString())
        } catch (e: Exception) { ToolResult.error(id, "Close error: ${e.message}") }
    }

    // ── Get foreground app ────────────────────────────────────────────────
    fun getForegroundApp(): ToolResult {
        val svc = accessibilityService
            ?: return ToolResult.error(id, "AccessibilityService not running")
        return try {
            val root = svc.rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: "unknown"
            ToolResult.success(id, JSONObject().apply { put("foreground_app", pkg) }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Error") }
    }

    // ── List installed apps ───────────────────────────────────────────────
    fun listInstalledApps(launchableOnly: Boolean = true): ToolResult {
        return try {
            val pm = context.packageManager
            val apps = if (launchableOnly) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0).map { ri ->
                    JSONObject().apply {
                        put("package", ri.activityInfo.packageName)
                        put("label", ri.loadLabel(pm).toString())
                    }.toString()
                }
            } else {
                pm.getInstalledPackages(0).map { pi ->
                    JSONObject().apply {
                        put("package", pi.packageName)
                        put("version", pi.versionName ?: "")
                    }.toString()
                }
            }
            ToolResult.success(id, JSONObject().apply {
                put("apps", apps.take(100)); put("count", apps.size)
            }.toString())
        } catch (e: Exception) { ToolResult.error(id, e.message ?: "Error") }
    }

    // ── Find element by text and tap it ───────────────────────────────────
    suspend fun tapByText(text: String, partialMatch: Boolean = true): ToolResult =
        withContext(Dispatchers.IO) {
            val svc = accessibilityService
                ?: return@withContext ToolResult.error(id, "AccessibilityService not running")
            try {
                val root = svc.rootInActiveWindow
                    ?: return@withContext ToolResult.error(id, "No active window")
                val node = findNodeByText(root, text, partialMatch)
                    ?: return@withContext ToolResult.success(id, JSONObject().put("found", false).toString())
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ToolResult.success(id, JSONObject().apply {
                    put("found", true); put("tapped", text)
                    put("class", node.className)
                }.toString())
            } catch (e: Exception) { ToolResult.error(id, e.message ?: "Tap error") }
        }

    // ── Fill a text field by label/hint ───────────────────────────────────
    suspend fun fillField(hintOrLabel: String, text: String): ToolResult =
        withContext(Dispatchers.IO) {
            val svc = accessibilityService
                ?: return@withContext ToolResult.error(id, "AccessibilityService not running")
            try {
                val root = svc.rootInActiveWindow
                    ?: return@withContext ToolResult.error(id, "No active window")
                val node = findNodeByText(root, hintOrLabel, true)
                    ?: return@withContext ToolResult.error(id, "Field '$hintOrLabel' not found")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(200)
                val args = Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ToolResult.success(id, JSONObject().apply {
                    put("field", hintOrLabel); put("filled", true)
                }.toString())
            } catch (e: Exception) { ToolResult.error(id, e.message ?: "Fill error") }
        }

    // ── Run multi-step workflow ────────────────────────────────────────────
    suspend fun runWorkflow(name: String, steps: List<JSONObject>): ToolResult {
        val results = mutableListOf<JSONObject>()
        for ((i, step) in steps.withIndex()) {
            val action = step.optString("action")
            val result = when (action) {
                "launch" -> launchApp(step.getString("package"))
                "tap_text" -> tapByText(step.getString("text"), step.optBoolean("partial", true))
                "fill" -> fillField(step.getString("field"), step.getString("value"))
                "wait" -> { delay(step.optLong("ms", 1000)); ToolResult.success(id, JSONObject().put("waited", true).toString()) }
                "back" -> {
                    accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ToolResult.success(id, JSONObject().put("back", true).toString())
                }
                "home" -> {
                    accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    ToolResult.success(id, JSONObject().put("home", true).toString())
                }
                else -> ToolResult.error(id, "Unknown workflow step: $action")
            }
            results.add(JSONObject().apply {
                put("step", i); put("action", action); put("ok", result.success)
                if (!result.success) put("error", result.error)
            })
        }
        val okCount = results.count { it.getBoolean("ok") }
        return ToolResult.success(id, JSONObject().apply {
            put("workflow", name); put("steps", steps.size); put("ok", okCount); put("results", results.toString())
        }.toString())
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String, partial: Boolean): AccessibilityNodeInfo? {
        val q = text.lowercase()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val nodeText = (child.text ?: child.contentDescription ?: child.hintText)?.toString()?.lowercase()
            if (nodeText != null && (if (partial) nodeText.contains(q) else nodeText == q)) {
                if (child.isClickable) return child
            }
            val found = findNodeByText(child, text, partial)
            if (found != null) return found
        }
        return null
    }

    private fun resolvePackage(name: String): String {
        val knownApps = mapOf(
            "chrome" to "com.android.chrome", "firefox" to "org.mozilla.firefox",
            "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger", "discord" to "com.discord",
            "spotify" to "com.spotify.music", "camera" to "com.android.camera2",
            "settings" to "com.android.settings", "calculator" to "com.android.calculator2",
            "photos" to "com.google.android.apps.photos"
        )
        return knownApps[name.lowercase()] ?: name
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "launch")) {
            "launch" -> launchApp((params["package"] ?: return ToolResult.error(id, "Missing required parameter: " + "package")))
            "close" -> closeApp((params["package"] ?: return ToolResult.error(id, "Missing required parameter: " + "package")))
            "foreground" -> getForegroundApp()
            "list" -> listInstalledApps((params["launchable_only"]?.toBoolean() ?: true))
            "tap_text" -> tapByText((params["text"] ?: return ToolResult.error(id, "Missing required parameter: " + "text")), (params["partial"]?.toBoolean() ?: true))
            "fill" -> fillField((params["field"] ?: return ToolResult.error(id, "Missing required parameter: " + "field")), (params["value"] ?: return ToolResult.error(id, "Missing required parameter: " + "value")))
            "workflow" -> {
                val stepsArr = org.json.JSONArray(params["steps"] ?: "[]")
                val steps = (0 until stepsArr.length()).map { JSONObject(stepsArr.getString(it)) }
                runWorkflow((params["name"] ?: "workflow"), steps)
            }
            else -> ToolResult.error(id, "Unknown app automation action: $action")
        }
    }
}
