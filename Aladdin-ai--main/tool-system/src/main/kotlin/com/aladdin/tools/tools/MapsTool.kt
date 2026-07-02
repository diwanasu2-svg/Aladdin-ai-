package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Phase 9 — Maps Tool
 * Location, directions, search, navigation, favorites via Android LocationManager + Google Maps Intent.
 */
@Singleton
class MapsTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool() {

    override val name = "maps"
    override val description = "Location, directions, nearby search, navigation, and saved places"

    private val savedPlaces = mutableMapOf<String, JSONObject>()

    // ── Current location ──────────────────────────────────────────────────
    suspend fun getCurrentLocation(): ToolResult = withContext(Dispatchers.IO) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            val providers = lm.getProviders(true)
            var best: Location? = null
            for (provider in providers) {
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            }
            if (best == null) return@withContext ToolResult.error("Location unavailable — check permissions")
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(best.latitude, best.longitude, 1)
            val address = addresses?.firstOrNull()
            ToolResult.success(JSONObject().apply {
                put("lat", best.latitude)
                put("lng", best.longitude)
                put("accuracy_m", best.accuracy)
                put("address", address?.getAddressLine(0) ?: "")
                put("city", address?.locality ?: "")
                put("country", address?.countryName ?: "")
            })
        } catch (e: Exception) {
            ToolResult.error("Location error: ${e.message}")
        }
    }

    // ── Search / Navigate via Google Maps ────────────────────────────────
    fun openNavigation(destination: String, mode: String = "d") {
        // mode: d=driving, w=walking, b=bicycling, r=transit
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$mode")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun searchNearby(query: String, lat: Double? = null, lng: Double? = null) {
        val uri = if (lat != null && lng != null)
            Uri.parse("geo:$lat,$lng?q=${Uri.encode(query)}")
        else
            Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── Geocode an address ────────────────────────────────────────────────
    suspend fun geocodeAddress(address: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocationName(address, 3)
            if (results.isNullOrEmpty()) return@withContext ToolResult.error("Address not found")
            val first = results[0]
            ToolResult.success(JSONObject().apply {
                put("address", first.getAddressLine(0) ?: address)
                put("lat", first.latitude)
                put("lng", first.longitude)
                put("city", first.locality ?: "")
                put("country", first.countryName ?: "")
            })
        } catch (e: Exception) {
            ToolResult.error("Geocoding error: ${e.message}")
        }
    }

    // ── Save / list favorite places ───────────────────────────────────────
    fun saveFavoritePlace(label: String, address: String, notes: String = ""): ToolResult {
        savedPlaces[label] = JSONObject().apply {
            put("label", label); put("address", address)
            put("notes", notes); put("saved_at", System.currentTimeMillis())
        }
        return ToolResult.success(JSONObject().apply {
            put("saved", label); put("address", address)
        })
    }

    fun listFavoritePlaces(): ToolResult {
        val places = savedPlaces.values.map { it.toString() }
        return ToolResult.success(JSONObject().apply {
            put("places", places); put("count", places.size)
        })
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        return when (val action = params.optString("action", "location")) {
            "location" -> getCurrentLocation()
            "navigate" -> {
                openNavigation(params.getString("destination"), params.optString("mode", "d"))
                ToolResult.success(JSONObject().put("navigation_started", true))
            }
            "search" -> {
                searchNearby(params.getString("query"),
                    if (params.has("lat")) params.getDouble("lat") else null,
                    if (params.has("lng")) params.getDouble("lng") else null)
                ToolResult.success(JSONObject().put("search_opened", true))
            }
            "geocode" -> geocodeAddress(params.getString("address"))
            "save_place" -> saveFavoritePlace(
                params.getString("label"), params.getString("address"), params.optString("notes", ""))
            "list_places" -> listFavoritePlaces()
            else -> ToolResult.error("Unknown maps action: $action")
        }
    }
}
