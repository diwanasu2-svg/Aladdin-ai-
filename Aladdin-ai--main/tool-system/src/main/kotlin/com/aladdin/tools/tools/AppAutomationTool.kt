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
class AppAutomationTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool() {

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
                ?: return ToolResult.error("App not found: $resolvedPkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult.success(JSONObject().apply {
                put("launched", resolvedPkg); put("status", "started")
            })
        } catch (e: Exception) { ToolResult.error("Launch error: ${e.message}") }
    }

    // ── Close app ─────────────────────────────────────────────────────────
    fun closeApp(packageName: String): ToolResult {
        return try {
            val svc = accessibilityService
                ?: return ToolResult.error("AccessibilityService not running")
            // Press back / use forceStopPackage (requires FORCE_STOP_PACKAGES permission)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ToolResult.success(JSONObject().apply { put("close_initiated", packageName) })
        } catch (e: Exception) { ToolResult.error("Close error: ${e.message}") }
    }

    // ── Get foreground app ────────────────────────────────────────────────
    fun getForegroundApp(): ToolResult {
        val svc = accessibilityService
            ?: return ToolResult.error("AccessibilityService not running")
        return try {
            val root = svc.rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: "unknown"
            ToolResult.success(JSONObject().apply { put("foreground_app", pkg) })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Error") }
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
            ToolResult.success(JSONObject().apply {
                put("apps", apps.take(100)); put("count", apps.size)
            })
        } catch (e: Exception) { ToolResult.error(e.message ?: "Error") }
    }

    // ── Find element by text and tap it ───────────────────────────────────
    suspend fun tapByText(text: String, partialMatch: Boolean = true): ToolResult =
        withContext(Dispatchers.IO) {
            val svc = accessibilityService
                ?: return@withContext ToolResult.error("AccessibilityService not running")
            try {
                val root = svc.rootInActiveWindow
                    ?: return@withContext ToolResult.error("No active window")
                val node = findNodeByText(root, text, partialMatch)
                    ?: return@withContext ToolResult.success(JSONObject().put("found", false))
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ToolResult.success(JSONObject().apply {
                    put("found", true); put("tapped", text)
                    put("class", node.className)
                })
            } catch (e: Exception) { ToolResult.error(e.message ?: "Tap error") }
        }

    // ── Fill a text field by label/hint ───────────────────────────────────
    suspend fun fillField(hintOrLabel: String, text: String): ToolResult =
        withContext(Dispatchers.IO) {
            val svc = accessibilityService
                ?: return@withContext ToolResult.error("AccessibilityService not running")
            try {
                val root = svc.rootInActiveWindow
                    ?: return@withContext ToolResult.error("No active window")
                val node = findNodeByText(root, hintOrLabel, true)
                    ?: return@withContext ToolResult.error("Field '$hintOrLabel' not found")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(200)
                val args = Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ToolResult.success(JSONObject().apply {
                    put("field", hintOrLabel); put("filled", true)
                })
            } catch (e: Exception) { ToolResult.error(e.message ?: "Fill error") }
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
                "wait" -> { delay(step.optLong("ms", 1000)); ToolResult.success(JSONObject().put("waited", true)) }
                "back" -> {
                    accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ToolResult.success(JSONObject().put("back", true))
                }
                "home" -> {
                    accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    ToolResult.success(JSONObject().put("home", true))
                }
                else -> ToolResult.error("Unknown workflow step: $action")
            }
            results.add(JSONObject().apply {
                put("step", i); put("action", action); put("ok", result.success)
                if (!result.success) put("error", result.error)
            })
        }
        val okCount = results.count { it.getBoolean("ok") }
        return ToolResult.success(JSONObject().apply {
            put("workflow", name); put("steps", steps.size); put("ok", okCount); put("results", results.toString())
        })
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

    override suspend fun execute(params: JSONObject): ToolResult {
        return when (val action = params.optString("action", "launch")) {
            "launch" -> launchApp(params.getString("package"))
            "close" -> closeApp(params.getString("package"))
            "foreground" -> getForegroundApp()
            "list" -> listInstalledApps(params.optBoolean("launchable_only", true))
            "tap_text" -> tapByText(params.getString("text"), params.optBoolean("partial", true))
            "fill" -> fillField(params.getString("field"), params.getString("value"))
            "workflow" -> {
                val stepsArr = params.getJSONArray("steps")
                val steps = (0 until stepsArr.length()).map { JSONObject(stepsArr.getString(it)) }
                runWorkflow(params.optString("name", "workflow"), steps)
            }
            else -> ToolResult.error("Unknown app automation action: $action")
        }
    }
}
