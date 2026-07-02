package com.aladdin.tools.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Weather tool — OpenWeatherMap API.
 *
 * Commands:
 *   current  → current conditions for a city or lat/lon
 *   forecast → 5-day / 3-hour forecast
 *   hourly   → next 24-hour summary
 *
 * Params:
 *   location   — city name (e.g., "London") or "lat,lon"
 *   units      — metric | imperial | standard
 *   api_key    — OWM API key (or set OPENWEATHER_API_KEY env)
 *   command    — current | forecast | hourly
 */
@Singleton
class WeatherTool @Inject constructor(
    private val context: Context
) : BaseTool {

    override val id = "weather.fetch"
    override val name = "Weather"
    override val description = "Fetches current weather and 5-day forecast via OpenWeatherMap"

    companion object {
        private const val TAG = "WeatherTool"
        private const val BASE_URL = "https://api.openweathermap.org/data/2.5"
        private const val GEOCODE_URL = "https://api.openweathermap.org/geo/1.0"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // ─── Entry point ──────────────────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val apiKey = params["api_key"]
            ?: context.getString(context.resources.getIdentifier("openweather_api_key", "string", context.packageName))
                .takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.error(id, "Missing OpenWeatherMap API key. Provide 'api_key' parameter or set openweather_api_key string resource.")

        val location = params["location"] ?: "current"
        val units = params["units"] ?: "metric"
        val command = params["command"] ?: "current"

        val (lat, lon) = resolveLocation(location, apiKey)
            ?: return@withContext ToolResult.error(id, "Could not resolve location: $location")

        return@withContext when (command) {
            "forecast" -> fetchForecast(lat, lon, units, apiKey)
            "hourly"   -> fetchHourly(lat, lon, units, apiKey)
            else       -> fetchCurrent(lat, lon, units, apiKey)
        }
    }

    // ─── Current weather ──────────────────────────────────────────────────────

    private fun fetchCurrent(lat: Double, lon: Double, units: String, key: String): ToolResult {
        val url = "$BASE_URL/weather?lat=$lat&lon=$lon&units=$units&appid=$key"
        return try {
            val body = get(url)
            val data = gson.fromJson(body, Map::class.java)

            val cityName = data["name"] as? String ?: "Unknown"
            val sys = data["sys"] as? Map<*, *>
            val country = sys?.get("country") as? String ?: ""
            val main = data["main"] as? Map<*, *> ?: emptyMap<String, Any>()
            val weatherList = data["weather"] as? List<*>
            val weather = weatherList?.firstOrNull() as? Map<*, *>
            val wind = data["wind"] as? Map<*, *> ?: emptyMap<String, Any>()

            val temp = main["temp"]?.toString()?.toDoubleOrNull()?.let { "%.1f".format(it) } ?: "--"
            val feelsLike = main["feels_like"]?.toString()?.toDoubleOrNull()?.let { "%.1f".format(it) } ?: "--"
            val humidity = main["humidity"]?.toString() ?: "--"
            val description = (weather?.get("description") as? String)?.replaceFirstChar { it.uppercase() } ?: "Unknown"
            val windSpeed = wind["speed"]?.toString() ?: "0"
            val tempUnit = if (units == "imperial") "°F" else "°C"
            val speedUnit = if (units == "imperial") "mph" else "m/s"

            val result = buildString {
                appendLine("📍 $cityName, $country")
                appendLine("🌤 $description")
                appendLine("🌡 Temp: $temp$tempUnit (feels like $feelsLike$tempUnit)")
                appendLine("💧 Humidity: $humidity%")
                appendLine("💨 Wind: $windSpeed $speedUnit")
            }
            ToolResult.success(id, result.trim())
        } catch (e: Exception) {
            ToolResult.error(id, "Weather fetch failed: ${e.message}")
        }
    }

    // ─── 5-day forecast ───────────────────────────────────────────────────────

    private fun fetchForecast(lat: Double, lon: Double, units: String, key: String): ToolResult {
        val url = "$BASE_URL/forecast?lat=$lat&lon=$lon&units=$units&appid=$key"
        return try {
            val body = get(url)
            val data = gson.fromJson(body, Map::class.java)
            val list = (data["list"] as? List<*>) ?: emptyList<Any>()
            val city = ((data["city"] as? Map<*, *>)?.get("name") as? String) ?: "Unknown"
            val tempUnit = if (units == "imperial") "°F" else "°C"

            // Group by date (every 8 items = 1 day in 3-hour increments)
            val days = list.chunked(8)
            val sb = StringBuilder("📅 5-Day Forecast for $city:\n\n")
            val sdf = java.text.SimpleDateFormat("EEE MMM dd", java.util.Locale.US)

            days.take(5).forEach { dayItems ->
                val item = dayItems[dayItems.size / 2] as? Map<*, *> ?: return@forEach
                val main = item["main"] as? Map<*, *> ?: return@forEach
                val weatherDesc = ((item["weather"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("description") as? String ?: ""
                val dt = (item["dt"] as? Double)?.toLong()?.let { sdf.format(java.util.Date(it * 1000)) } ?: "--"
                val minT = main["temp_min"]?.toString()?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "--"
                val maxT = main["temp_max"]?.toString()?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "--"
                sb.appendLine("$dt: $minT–$maxT$tempUnit, ${weatherDesc.replaceFirstChar { it.uppercase() }}")
            }
            ToolResult.success(id, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult.error(id, "Forecast fetch failed: ${e.message}")
        }
    }

    // ─── Next 24-hour hourly ──────────────────────────────────────────────────

    private fun fetchHourly(lat: Double, lon: Double, units: String, key: String): ToolResult {
        val url = "$BASE_URL/forecast?lat=$lat&lon=$lon&units=$units&cnt=8&appid=$key"
        return try {
            val body = get(url)
            val data = gson.fromJson(body, Map::class.java)
            val list = (data["list"] as? List<*>) ?: emptyList<Any>()
            val tempUnit = if (units == "imperial") "°F" else "°C"
            val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

            val sb = StringBuilder("🕐 Next 24 Hours:\n\n")
            list.take(8).forEach { raw ->
                val item = raw as? Map<*, *> ?: return@forEach
                val main = item["main"] as? Map<*, *> ?: return@forEach
                val dt = (item["dt"] as? Double)?.toLong()?.let { timeFmt.format(java.util.Date(it * 1000)) } ?: "--"
                val temp = main["temp"]?.toString()?.toDoubleOrNull()?.let { "%.0f".format(it) } ?: "--"
                val desc = ((item["weather"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("main") as? String ?: ""
                val pop = (item["pop"] as? Double)?.let { " ☔${(it * 100).toInt()}%" } ?: ""
                sb.appendLine("$dt: $temp$tempUnit $desc$pop")
            }
            ToolResult.success(id, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult.error(id, "Hourly fetch failed: ${e.message}")
        }
    }

    // ─── Location resolution ──────────────────────────────────────────────────

    private fun resolveLocation(location: String, apiKey: String): Pair<Double, Double>? {
        if (location == "current") return getDeviceLocation()

        // Check if it's already lat,lon
        val parts = location.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            if (lat != null && lon != null) return Pair(lat, lon)
        }

        // Geocode city name
        return try {
            val url = "$GEOCODE_URL/direct?q=${location.replace(" ", "+")}&limit=1&appid=$apiKey"
            val body = get(url)
            val list = gson.fromJson(body, List::class.java)
            val first = list?.firstOrNull() as? Map<*, *> ?: return null
            val lat = (first["lat"] as? Double) ?: return null
            val lon = (first["lon"] as? Double) ?: return null
            Pair(lat, lon)
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed: ${e.message}")
            null
        }
    }

    private fun getDeviceLocation(): Pair<Double, Double>? {
        // Stub — in production, call FusedLocationProviderClient
        // Returns null if no location permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return if (hasPermission) {
            // Fallback: London as default until FusedLocation is wired
            Pair(51.5074, -0.1278)
        } else null
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
            resp.body?.string() ?: throw Exception("Empty response")
        }
    }
}
