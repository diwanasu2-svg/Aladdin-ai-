package com.aladdin.tools.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App Launcher tool — PackageManager.
 *
 * Commands:
 *   launch   — launch an installed app by name or package
 *   list     — list all installed launchable apps
 *   find     — search for apps by name
 *   info     — get info about a specific app
 *   kill     — (stub) suggest closing an app
 *
 * Params: command, app_name, package_name, query
 */
@Singleton
class AppLauncherTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "app_launcher"
    override val name = "App Launcher"
    override val description = "Launch, list, and query installed Android apps via PackageManager"

    companion object {
        private const val TAG = "AppLauncherTool"

        /** Well-known app name → package mappings for fast resolution. */
        private val KNOWN_APPS = mapOf(
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "youtube" to "com.google.android.youtube",
            "camera" to "com.android.camera2",
            "photos" to "com.google.android.apps.photos",
            "calendar" to "com.google.android.calendar",
            "calculator" to "com.android.calculator2",
            "settings" to "com.android.settings",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "amazon" to "com.amazon.mShop.android.shopping",
            "uber" to "com.ubercab",
            "lyft" to "me.lyft.android",
            "slack" to "com.Slack",
            "zoom" to "us.zoom.videomeetings",
            "phone" to "com.android.dialer",
            "messages" to "com.google.android.apps.messaging",
            "contacts" to "com.android.contacts",
            "files" to "com.google.android.apps.nbu.files",
            "drive" to "com.google.android.apps.docs",
            "docs" to "com.google.android.apps.docs.editors.docs",
            "sheets" to "com.google.android.apps.docs.editors.sheets",
            "meet" to "com.google.android.apps.meetings",
            "clock" to "com.android.deskclock",
            "browser" to "com.android.browser",
            "play store" to "com.android.vending",
            "music" to "com.google.android.music"
        )
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "launch") {
            "list"  -> listApps(params)
            "find"  -> findApps(params)
            "info"  -> appInfo(params)
            "kill"  -> ToolResult.success(id, "App termination is not allowed from assistant context.")
            else    -> launchApp(params)
        }
    }

    private fun launchApp(params: Map<String, String>): ToolResult {
        val appName = params["app_name"] ?: params["app"] ?: params["query"]
        val packageName = params["package_name"]
            ?: appName?.let { resolvePackage(it) }
            ?: return ToolResult.error(id, "Provide 'app_name' or 'package_name'")

        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?: return ToolResult.error(id, "App '$packageName' not installed or not launchable")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val label = getAppLabel(pm, packageName)
            Log.i(TAG, "Launched: $label ($packageName)")
            ToolResult.success(id, "🚀 Launched: $label")
        } catch (e: Exception) {
            ToolResult.error(id, "Launch failed: ${e.message}")
        }
    }

    private fun listApps(params: Map<String, String>): ToolResult {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedBy { it.first }

        val limit = params["limit"]?.toIntOrNull() ?: 30
        val sb = StringBuilder("📱 Installed Apps (${apps.size} total, showing $limit):\n\n")
        apps.take(limit).forEach { (label, pkg) ->
            sb.appendLine("• $label  [$pkg]")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private fun findApps(params: Map<String, String>): ToolResult {
        val query = params["query"] ?: return ToolResult.error(id, "Missing query")
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val matches = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { (label, pkg) ->
                label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)
            }

        if (matches.isEmpty()) return ToolResult.success(id, "No apps matching '$query'")
        val sb = StringBuilder("🔍 Apps matching '$query':\n\n")
        matches.forEach { (label, pkg) -> sb.appendLine("• $label  [$pkg]") }
        return ToolResult.success(id, sb.toString().trim())
    }

    private fun appInfo(params: Map<String, String>): ToolResult {
        val appName = params["app_name"] ?: params["query"]
        val packageName = params["package_name"]
            ?: appName?.let { resolvePackage(it) }
            ?: return ToolResult.error(id, "Provide 'app_name' or 'package_name'")

        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            val appInfo = info.applicationInfo
            val label = pm.getApplicationLabel(appInfo).toString()
            val version = info.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            ToolResult.success(id, buildString {
                appendLine("📱 $label")
                appendLine("  Package: $packageName")
                appendLine("  Version: $version ($versionCode)")
                appendLine("  Target SDK: ${appInfo.targetSdkVersion}")
                appendLine("  Installed: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(info.firstInstallTime))}")
            }.trim())
        } catch (e: PackageManager.NameNotFoundException) {
            ToolResult.error(id, "App '$packageName' not found")
        }
    }

    private fun resolvePackage(name: String): String? {
        val lower = name.trim().lowercase()
        KNOWN_APPS[lower]?.let { return it }
        // Fuzzy match against known apps
        for ((key, pkg) in KNOWN_APPS) {
            if (lower.contains(key) || key.contains(lower)) return pkg
        }
        // Try to find via PackageManager
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(intent, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?.activityInfo?.packageName
    }

    private fun getAppLabel(pm: PackageManager, packageName: String): String =
        try { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
        catch (_: Exception) { packageName }
}
