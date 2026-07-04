package com.aladdin.app.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Debug
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HealthMonitor — Item 84: Comprehensive System Health Monitoring
 *
 * Monitors: CPU, RAM, Battery, Microphone, Network
 * Emits HealthReport every 30 seconds.
 * Triggers alerts when thresholds are exceeded.
 */
@Singleton
class HealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HealthMonitor"
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val CPU_WARN_THRESHOLD  = 80f   // %
        private const val RAM_WARN_THRESHOLD  = 85f   // %
        private const val BATT_WARN_THRESHOLD = 15f   // %
    }

    data class HealthReport(
        val timestampMs: Long      = System.currentTimeMillis(),
        val cpuPercent: Float      = 0f,
        val ramUsedMb: Long        = 0L,
        val ramTotalMb: Long       = 0L,
        val ramPercent: Float      = 0f,
        val batteryPercent: Float  = 100f,
        val isCharging: Boolean    = false,
        val isMicAvailable: Boolean = true,
        val networkType: String    = "NONE",
        val isNetworkAvailable: Boolean = false,
        val alerts: List<HealthAlert> = emptyList()
    )

    enum class HealthAlert { HIGH_CPU, LOW_RAM, LOW_BATTERY, MIC_UNAVAILABLE, NO_NETWORK }

    private val _healthFlow = MutableStateFlow(HealthReport())
    val healthFlow: StateFlow<HealthReport> = _healthFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            Log.i(TAG, "HealthMonitor started")
            while (isActive) {
                _healthFlow.value = collectReport()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        Log.i(TAG, "HealthMonitor stopped")
    }

    fun getLatestReport(): HealthReport = _healthFlow.value

    // ── Data collection ───────────────────────────────────────────────────────

    private fun collectReport(): HealthReport {
        val cpu      = readCpuUsage()
        val (ramUsed, ramTotal) = readRam()
        val ramPct   = if (ramTotal > 0) ramUsed.toFloat() / ramTotal * 100f else 0f
        val (battPct, charging) = readBattery()
        val micOk    = isMicAvailable()
        val (netType, netOk) = readNetwork()

        val alerts = buildList {
            if (cpu        > CPU_WARN_THRESHOLD)  add(HealthAlert.HIGH_CPU)
            if (ramPct     > RAM_WARN_THRESHOLD)  add(HealthAlert.LOW_RAM)
            if (battPct    < BATT_WARN_THRESHOLD && !charging) add(HealthAlert.LOW_BATTERY)
            if (!micOk)                           add(HealthAlert.MIC_UNAVAILABLE)
            if (!netOk)                           add(HealthAlert.NO_NETWORK)
        }

        if (alerts.isNotEmpty()) Log.w(TAG, "Health alerts: $alerts")

        return HealthReport(
            cpuPercent          = cpu,
            ramUsedMb           = ramUsed,
            ramTotalMb          = ramTotal,
            ramPercent          = ramPct,
            batteryPercent      = battPct,
            isCharging          = charging,
            isMicAvailable      = micOk,
            networkType         = netType,
            isNetworkAvailable  = netOk,
            alerts              = alerts
        )
    }

    private fun readCpuUsage(): Float {
        return try {
            val stat = File("/proc/stat").readLines().firstOrNull() ?: return 0f
            val vals = stat.split(" ").drop(1).mapNotNull { it.toLongOrNull() }
            if (vals.size < 8) return 0f
            val idle    = vals[3] + vals[4]
            val total   = vals.sum()
            if (total == 0L) return 0f
            (1f - idle.toFloat() / total.toFloat()) * 100f
        } catch (_: Exception) { 0f }
    }

    private fun readRam(): Pair<Long, Long> {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .getMemoryInfo(mi)
            val totalMb = mi.totalMem / 1_048_576L
            val usedMb  = (mi.totalMem - mi.availMem) / 1_048_576L
            Pair(usedMb, totalMb)
        } catch (_: Exception) {
            val used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576L
            Pair(used, Runtime.getRuntime().maxMemory() / 1_048_576L)
        }
    }

    private fun readBattery(): Pair<Float, Boolean> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct    = if (scale > 0) level.toFloat() / scale.toFloat() * 100f else 100f
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, charging)
        } catch (_: Exception) { Pair(100f, false) }
    }

    private fun isMicAvailable(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            (!am.isMicrophoneMute)
        } catch (_: Exception) { true }
    }

    private fun readNetwork(): Pair<String, Boolean> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return Pair("NONE", false)
            val caps = cm.getNetworkCapabilities(net) ?: return Pair("NONE", false)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            Pair(type, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        } catch (_: Exception) { Pair("UNKNOWN", false) }
    }
}
