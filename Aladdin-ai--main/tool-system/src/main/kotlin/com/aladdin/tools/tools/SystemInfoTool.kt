package com.aladdin.tools.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System Info tool.
 *
 * Commands:
 *   all      — full system report
 *   battery  — battery percentage, charging state, temperature
 *   storage  — internal + external storage usage
 *   memory   — RAM usage (total / available / used)
 *   network  — WiFi / mobile / VPN status + signal type
 *   device   — device model, OS version, API level
 *
 * Params: command
 */
@Singleton
class SystemInfoTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "system_info"
    override val name = "System Info"
    override val description = "Reports battery, storage, memory, network, and device info"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        when (params["command"] ?: "all") {
            "battery" -> batteryInfo()
            "storage" -> storageInfo()
            "memory"  -> memoryInfo()
            "network" -> networkInfo()
            "device"  -> deviceInfo()
            else      -> fullReport()
        }
    }

    private fun batteryInfo(): ToolResult {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val chargeType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC  -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Unplugged"
        }
        val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempC = if (tempRaw > 0) tempRaw / 10f else -1f

        val emoji = when {
            pct > 80 -> "🔋"
            pct > 20 -> "🪫"
            else     -> "⚠️"
        }
        return ToolResult.success(id, buildString {
            appendLine("$emoji Battery: $pct%")
            appendLine("  Charging: $isCharging ($chargeType)")
            if (tempC > 0) appendLine("  Temperature: ${"%.1f".format(tempC)}°C")
        }.trim())
    }

    private fun storageInfo(): ToolResult {
        val internal = StatFs(Environment.getDataDirectory().path)
        val intTotal = internal.totalBytes
        val intAvail = internal.availableBytes
        val intUsed  = intTotal - intAvail

        val sb = StringBuilder("💾 Storage:\n")
        sb.appendLine("  Internal: ${formatSize(intUsed)} used / ${formatSize(intTotal)} total (${percent(intUsed, intTotal)}% used)")

        val extState = Environment.getExternalStorageState()
        if (extState == Environment.MEDIA_MOUNTED) {
            val external = StatFs(Environment.getExternalStorageDirectory().path)
            val extTotal = external.totalBytes
            val extAvail = external.availableBytes
            val extUsed  = extTotal - extAvail
            sb.appendLine("  External: ${formatSize(extUsed)} used / ${formatSize(extTotal)} total")
        } else {
            sb.appendLine("  External: not available ($extState)")
        }
        return ToolResult.success(id, sb.toString().trim())
    }

    private fun memoryInfo(): ToolResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val total = memInfo.totalMem
        val avail = memInfo.availMem
        val used  = total - avail
        val lowMem = memInfo.lowMemory

        return ToolResult.success(id, buildString {
            appendLine("🧠 Memory:")
            appendLine("  Total: ${formatSize(total)}")
            appendLine("  Used:  ${formatSize(used)} (${percent(used, total)}%)")
            appendLine("  Free:  ${formatSize(avail)}")
            if (lowMem) appendLine("  ⚠ Low memory warning!")
        }.trim())
    }

    private fun networkInfo(): ToolResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)

        if (capabilities == null) {
            return ToolResult.success(id, "📡 Network: No active connection")
        }

        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)      -> "VPN"
            else -> "Unknown"
        }
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val downMbps = capabilities.linkDownstreamBandwidthKbps / 1000
        val upMbps   = capabilities.linkUpstreamBandwidthKbps / 1000

        return ToolResult.success(id, buildString {
            appendLine("📡 Network: $type")
            appendLine("  Internet: $hasInternet")
            appendLine("  Down: ${downMbps} Mbps / Up: ${upMbps} Mbps")
        }.trim())
    }

    private fun deviceInfo(): ToolResult {
        return ToolResult.success(id, buildString {
            appendLine("📱 Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("  Board: ${Build.BOARD}")
            appendLine("  CPU ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        }.trim())
    }

    private fun fullReport(): ToolResult {
        val sections = listOf(
            batteryInfo().output,
            storageInfo().output,
            memoryInfo().output,
            networkInfo().output,
            deviceInfo().output
        )
        return ToolResult.success(id, sections.joinToString("\n\n"))
    }

    private fun formatSize(bytes: Long) = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L          -> "%.1f KB".format(bytes / 1024.0)
        else                    -> "$bytes B"
    }

    private fun percent(used: Long, total: Long) =
        if (total > 0) (used * 100 / total) else 0
}
