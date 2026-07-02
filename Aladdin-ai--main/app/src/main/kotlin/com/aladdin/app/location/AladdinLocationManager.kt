package com.aladdin.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AladdinLocationManager — Item 68: GPS, Geocoder, reverse geocoding, location updates.
 */
@Singleton
class AladdinLocationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AladdinLocation"
        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val MIN_DISTANCE_M = 10f
    }

    data class LocationData(
        val latitude: Double, val longitude: Double,
        val accuracy: Float = 0f, val address: String = "",
        val city: String = "", val country: String = ""
    )

    private val _locationFlow = MutableStateFlow<LocationData?>(null)
    val locationFlow: StateFlow<LocationData?> = _locationFlow.asStateFlow()

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun startUpdates() {
        if (!hasPermission()) { Log.w(TAG, "Location permission not granted"); return }
        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build()
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { loc ->
                        scope.launch {
                            val data = enrichLocation(loc)
                            _locationFlow.value = data
                            Log.d(TAG, "Location updated: ${data.latitude},${data.longitude} — ${data.city}")
                        }
                    }
                }
            }
            fusedClient?.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        } catch (e: SecurityException) { Log.w(TAG, "Location permission denied: ${e.message}") }
          catch (e: Exception) { Log.e(TAG, "Location error: ${e.message}") }
    }

    fun stopUpdates() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null
    }

    suspend fun getCurrentLocation(): LocationData? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                        scope.launch { cont.resume(if (loc != null) enrichLocation(loc) else null) {} }
                    }?.addOnFailureListener { cont.resume(null) {} }
                } catch (_: SecurityException) { cont.resume(null) {} }
            }
        } catch (_: Exception) { null }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!Geocoder.isPresent()) return@withContext "$lat,$lon"
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            addresses?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.thoroughfare, addr.locality, addr.countryName)
                    .joinToString(", ")
            } ?: "$lat,$lon"
        } catch (e: Exception) { "$lat,$lon" }
    }

    private suspend fun enrichLocation(loc: Location): LocationData {
        val address = reverseGeocode(loc.latitude, loc.longitude)
        val parts   = address.split(", ")
        return LocationData(
            latitude  = loc.latitude,
            longitude = loc.longitude,
            accuracy  = loc.accuracy,
            address   = address,
            city      = parts.getOrNull(parts.size - 2) ?: "",
            country   = parts.lastOrNull() ?: ""
        )
    }
}
