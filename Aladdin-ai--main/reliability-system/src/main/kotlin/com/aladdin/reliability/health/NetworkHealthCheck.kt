package com.aladdin.reliability.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class NetworkHealthCheck(private val context: Context) {

    companion object {
        private const val PING_URL = "https://clients3.google.com/generate_204"
        private const val TIMEOUT_MS = 5_000
    }

    data class Result(val reachable: Boolean, val latencyMs: Long, val issue: String?)

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        if (!hasConnection()) return@withContext Result(false, -1, "No active network connection")
        try {
            val t0 = System.currentTimeMillis()
            val conn = URL(PING_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.connect()
            val code   = conn.responseCode
            val latency = System.currentTimeMillis() - t0
            conn.disconnect()
            val ok = code == 204 || code == 200
            Result(reachable = ok, latencyMs = latency,
                   issue = if (!ok) "Ping returned HTTP $code" else null)
        } catch (e: Exception) {
            Result(reachable = false, latencyMs = -1, issue = "Network ping failed: ${e.message}")
        }
    }

    private fun hasConnection(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
