package com.aladdin.app.intelligence

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 11 Item 5: Context & Location Awareness Engine ─────────────────────

@Singleton
class ContextLocationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "ContextEngine" }

    // ── Full device + environment context ─────────────────────────────────────
    data class FullContext(
        val timeOfDay: TimeOfDay,
        val dayOfWeek: String,
        val isWeekend: Boolean,
        val batteryPct: Int,
        val isCharging: Boolean,
        val isLowBattery: Boolean,
        val networkType: NetworkType,
        val isOnline: Boolean,
        val location: LocationContext?,
        val heapUsedMb: Float,
        val isQuietHours: Boolean   // 11pm–7am
    )

    data class LocationContext(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val locality: String = "",
        val country: String = "",
        val locationType: LocationType = LocationType.UNKNOWN
    )

    enum class TimeOfDay { EARLY_MORNING, MORNING, AFTERNOON, EVENING, NIGHT }
    enum class NetworkType { WIFI, CELLULAR_5G, CELLULAR_4G, CELLULAR_3G, OFFLINE }
    enum class LocationType { HOME, WORK, COMMUTING, SHOPPING, OUTDOOR, UNKNOWN }

    // ── Build full context snapshot ───────────────────────────────────────────
    suspend fun buildFullContext(): FullContext = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow  = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US) ?: "Unknown"
        val isWE = cal.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

        val battery      = getBatteryInfo()
        val network      = getNetworkType()
        val location     = getLastKnownLocation()
        val heapMb       = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1_048_576f)
        val quietHours   = hour < 7 || hour >= 23

        FullContext(
            timeOfDay    = when (hour) {
                in 5..7  -> TimeOfDay.EARLY_MORNING
                in 8..11 -> TimeOfDay.MORNING
                in 12..17 -> TimeOfDay.AFTERNOON
                in 18..21 -> TimeOfDay.EVENING
                else     -> TimeOfDay.NIGHT
            },
            dayOfWeek    = dow,
            isWeekend    = isWE,
            batteryPct   = battery.first,
            isCharging   = battery.second,
            isLowBattery = battery.first < 20 && !battery.second,
            networkType  = network,
            isOnline     = network != NetworkType.OFFLINE,
            location     = location,
            heapUsedMb   = heapMb,
            isQuietHours = quietHours
        )
    }

    // ── Battery info ──────────────────────────────────────────────────────────
    private fun getBatteryInfo(): Pair<Int, Boolean> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
            val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct    = (level * 100 / scale)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, charging)
        } catch (e: Exception) { Pair(100, false) }
    }

    // ── Network type ──────────────────────────────────────────────────────────
    private fun getNetworkType(): NetworkType {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkType.OFFLINE
            when {
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                        NetworkType.CELLULAR_5G else NetworkType.CELLULAR_4G
                }
                else -> NetworkType.OFFLINE
            }
        } catch (e: Exception) { NetworkType.OFFLINE }
    }

    // ── Last known location ───────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): LocationContext? = withContext(Dispatchers.IO) {
        try {
            val lm  = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return@withContext null

            val locality = reverseGeocode(loc)
            LocationContext(
                latitude   = loc.latitude,
                longitude  = loc.longitude,
                accuracyMeters = loc.accuracy,
                locality   = locality,
                country    = "",
                locationType = LocationType.UNKNOWN
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getLastKnownLocation error: ${e.message}")
            null
        }
    }

    private fun reverseGeocode(location: Location): String {
        return try {
            if (!Geocoder.isPresent()) return ""
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses: List<Address> = geocoder.getFromLocation(
                location.latitude, location.longitude, 1
            ) ?: return ""
            addresses.firstOrNull()?.locality ?: addresses.firstOrNull()?.subAdminArea ?: ""
        } catch (e: Exception) { "" }
    }

    // ── Context-aware proactive suggestions ───────────────────────────────────
    suspend fun getProactiveSuggestions(): List<String> {
        val ctx  = buildFullContext()
        val sugg = mutableListOf<String>()

        if (ctx.isLowBattery) sugg.add("Your battery is at ${ctx.batteryPct}% — would you like me to enable battery saver?")
        if (ctx.isQuietHours) sugg.add("It's quiet hours. I'll keep notifications minimal.")
        if (ctx.timeOfDay == TimeOfDay.MORNING && !ctx.isWeekend) sugg.add("Good morning! Would you like your daily briefing?")
        if (ctx.timeOfDay == TimeOfDay.EVENING) sugg.add("Good evening. Would you like a summary of today?")
        if (ctx.networkType == NetworkType.OFFLINE) sugg.add("You're offline. Switching to local AI models.")
        if (ctx.isWeekend && ctx.timeOfDay == TimeOfDay.MORNING) sugg.add("It's the weekend! Would you like some leisure suggestions?")
        if (ctx.location?.locality?.isNotBlank() == true) sugg.add("You're in ${ctx.location.locality}.")

        return sugg
    }

    // ── Format context as a system prompt injection ───────────────────────────
    suspend fun buildContextPrompt(): String {
        val ctx = buildFullContext()
        return buildString {
            append("[Context: ")
            append("Time=${ctx.timeOfDay.name.replace("_", " ").lowercase()}, ")
            append("Day=${ctx.dayOfWeek}, ")
            if (ctx.isWeekend) append("Weekend=yes, ")
            append("Battery=${ctx.batteryPct}%${if (ctx.isCharging) " charging" else ""}, ")
            append("Network=${ctx.networkType.name.replace("_", " ")}, ")
            if (ctx.location?.locality?.isNotBlank() == true) append("Location=${ctx.location.locality}, ")
            if (ctx.isLowBattery) append("LowBattery=true, ")
            if (ctx.isQuietHours) append("QuietHours=true, ")
            append("]")
        }
    }
}
