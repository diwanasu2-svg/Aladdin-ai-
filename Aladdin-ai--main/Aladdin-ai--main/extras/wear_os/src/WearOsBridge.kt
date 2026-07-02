// WearOsBridge.kt — Wear OS Companion Module
// Place in your Android/Wear OS project: app/src/main/java/com/aladdin/wear/WearOsBridge.kt
//
// Dependencies (build.gradle.kts):
//   implementation("com.google.android.gms:play-services-wearable:18.1.0")
//   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

package com.aladdin.wear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import org.json.JSONObject
import java.util.UUID

class WearOsBridge(private val context: Context) {

    companion object {
        private const val TAG = "AladdinWear"
        private const val BRIDGE_HOST = "127.0.0.1"
        private const val BRIDGE_PORT = 45678
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var onReplyReceived: ((String) -> Unit)? = null
    private var onConnected: (() -> Unit)? = null

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(
        onConnected: (() -> Unit)? = null,
        onReply: ((String) -> Unit)? = null,
    ) {
        this.onConnected = onConnected
        this.onReplyReceived = onReply
        scope.launch { connectLoop() }
    }

    private suspend fun connectLoop() {
        while (isActive) {
            try {
                Log.d(TAG, "Connecting to Aladdin bridge at $BRIDGE_HOST:$BRIDGE_PORT")
                socket = Socket(BRIDGE_HOST, BRIDGE_PORT)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                Log.i(TAG, "Connected to Aladdin AI")
                onConnected?.invoke()
                receiveLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}, retrying in ${RECONNECT_DELAY_MS}ms")
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun receiveLoop() {
        try {
            while (isActive) {
                val line = withContext(Dispatchers.IO) { reader?.readLine() } ?: break
                try {
                    handleMessage(JSONObject(line))
                } catch (e: Exception) {
                    Log.w(TAG, "Parse error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receive error: ${e.message}")
        }
        Log.w(TAG, "Disconnected from bridge")
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "ai_reply" -> {
                val text = msg.getJSONObject("payload").getString("text")
                Log.d(TAG, "AI reply: $text")
                onReplyReceived?.invoke(text)
            }
            "notification" -> {
                val payload = msg.getJSONObject("payload")
                showNotification(payload.getString("title"), payload.getString("body"))
            }
            "haptic" -> {
                vibrate(msg.getJSONObject("payload").optString("pattern", "click"))
            }
            "status_response" -> {
                val status = msg.getJSONObject("payload")
                Log.i(TAG, "Bridge status: ${status.getString("status")}")
            }
        }
    }

    // ── Send messages ─────────────────────────────────────────────────────────

    fun sendVoiceCommand(text: String) {
        send(JSONObject().apply {
            put("type", "voice_command")
            put("payload", JSONObject().put("text", text))
            put("timestamp", System.currentTimeMillis() / 1000.0)
            put("reply_id", UUID.randomUUID().toString())
        })
    }

    fun sendHealthData(steps: Int, heartRate: Int, calories: Double, sleepHours: Double = 0.0) {
        send(JSONObject().apply {
            put("type", "health_data")
            put("payload", JSONObject()
                .put("steps", steps)
                .put("heart_rate", heartRate)
                .put("calories", calories)
                .put("sleep_hours", sleepHours))
            put("timestamp", System.currentTimeMillis() / 1000.0)
        })
    }

    fun requestStatus() {
        send(JSONObject().apply {
            put("type", "status_request")
            put("payload", JSONObject())
            put("timestamp", System.currentTimeMillis() / 1000.0)
        })
    }

    private fun send(msg: JSONObject) {
        scope.launch {
            try {
                writer?.println(msg.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.message}")
            }
        }
    }

    // ── UI actions ────────────────────────────────────────────────────────────

    private fun showNotification(title: String, body: String) {
        // TODO: Use NotificationManager / VibratorManager on Wear OS
        Log.d(TAG, "Notification: $title — $body")
    }

    private fun vibrate(pattern: String) {
        // TODO: Use VibratorManagerCompat on Wear OS
        Log.d(TAG, "Haptic: $pattern")
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun disconnect() {
        scope.cancel()
        try { socket?.close() } catch (e: Exception) {}
    }
}
