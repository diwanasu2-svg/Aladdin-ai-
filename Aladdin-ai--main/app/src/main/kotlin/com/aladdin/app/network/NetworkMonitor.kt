package com.aladdin.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NetworkMonitor — Item 98: Real-time network status monitoring.
 *
 * Uses ConnectivityManager.NetworkCallback to track connectivity changes.
 * Exposes a StateFlow<NetworkState> for reactive UI updates.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "NetworkMonitor" }

    enum class NetworkType { NONE, WIFI, CELLULAR, ETHERNET, UNKNOWN }

    data class NetworkState(
        val isConnected: Boolean,
        val type: NetworkType,
        val isMetered: Boolean = false,
        val bandwidthKbps: Int = 0
    )

    private val _state = MutableStateFlow(NetworkState(false, NetworkType.NONE))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    val isConnected: Boolean get() = _state.value.isConnected

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { update(network) }
        override fun onLost(network: Network) {
            _state.value = NetworkState(false, NetworkType.NONE)
            Log.i(TAG, "Network lost")
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.UNKNOWN
            }
            val metered   = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val bandwidth = caps.linkDownstreamBandwidthKbps
            _state.value  = NetworkState(true, type, metered, bandwidth)
            Log.d(TAG, "Network: type=$type metered=$metered bandwidth=${bandwidth}kbps")
        }

        private fun update(network: Network) {
            val caps = cm.getNetworkCapabilities(network) ?: return
            onCapabilitiesChanged(network, caps)
        }
    }

    fun startMonitoring() {
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, callback)
            // Emit current state immediately
            _state.value = currentState()
            Log.i(TAG, "Network monitoring started. Connected=${_state.value.isConnected}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun stopMonitoring() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    fun currentState(): NetworkState {
        val activeNet  = cm.activeNetwork ?: return NetworkState(false, NetworkType.NONE)
        val caps       = cm.getNetworkCapabilities(activeNet) ?: return NetworkState(false, NetworkType.NONE)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
        return NetworkState(
            isConnected   = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            type          = type,
            isMetered     = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            bandwidthKbps = caps.linkDownstreamBandwidthKbps
        )
    }
}
