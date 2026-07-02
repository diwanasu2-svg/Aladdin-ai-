package com.aladdin.voicecore.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.aladdin.voicecore.models.ModelDownloader
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import com.aladdin.voicecore.models.VoiceCoreState
import com.aladdin.voicecore.service.ModelDownloadService
import com.aladdin.voicecore.service.VoiceCoreService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Convenience client for integrating Voice Core into any Activity/Fragment.
 *
 * Usage in Activity:
 * ```kotlin
 * private val voiceClient = VoiceCoreClient(this)
 *
 * override fun onStart() {
 *     super.onStart()
 *     voiceClient.connect()
 * }
 *
 * override fun onStop() {
 *     voiceClient.disconnect()
 *     super.onStop()
 * }
 *
 * // In onCreate or wherever:
 * voiceClient.onReady = {
 *     lifecycleScope.launch {
 *         voiceClient.events?.collect { event ->
 *             when (event) {
 *                 is VoiceCoreEvent.Transcript -> handleTranscript(event)
 *                 is VoiceCoreEvent.WakeWordDetected -> onWakeWord(event)
 *                 else -> {}
 *             }
 *         }
 *     }
 * }
 * ```
 */
class VoiceCoreClient(
    private val context: Context,
    private val config: VoiceCoreConfig = VoiceCoreConfig()
) {
    companion object {
        private const val TAG = "VoiceCoreClient"
    }

    /** Called when the service is bound and ready. */
    var onReady: (() -> Unit)? = null

    /** Called when the service disconnects unexpectedly. */
    var onDisconnected: (() -> Unit)? = null

    private var service: VoiceCoreService? = null

    val events: SharedFlow<VoiceCoreEvent>? get() = service?.events
    val state: StateFlow<VoiceCoreState>? get() = service?.state

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceCoreService.VoiceCoreBinder).service
            Log.i(TAG, "VoiceCoreService connected")
            onReady?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            Log.w(TAG, "VoiceCoreService disconnected")
            onDisconnected?.invoke()
        }
    }

    /** Ensure models are downloaded, then start and bind to the service. */
    fun connect(autoDownloadModels: Boolean = true) {
        if (autoDownloadModels && !ModelDownloader.areAllModelsReady(context)) {
            Log.i(TAG, "Models missing – starting download service")
            context.startService(Intent(context, ModelDownloadService::class.java))
        }

        val intent = VoiceCoreService.startIntent(context, config)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /** Unbind without stopping the service (it keeps running in foreground). */
    fun disconnect() {
        try { context.unbindService(connection) } catch (_: Exception) {}
        service = null
    }

    /** Unbind AND stop the service. */
    fun shutdown() {
        disconnect()
        context.startService(VoiceCoreService.stopIntent(context))
    }

    // ─── Forwarded API ────────────────────────────────────────────────────────

    fun speak(text: String) { service?.speak(text) ?: Log.w(TAG, "speak() called before connect()") }
    fun stopSpeaking() { service?.stopSpeaking() }
    fun wakeUp() { service?.wakeUp() }
    fun setSensitivity(value: Float) { service?.setSensitivity(value) }
    fun setConversationMode(enabled: Boolean) { service?.setConversationMode(enabled) }
}
