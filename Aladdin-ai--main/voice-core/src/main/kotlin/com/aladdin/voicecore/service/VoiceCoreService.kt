package com.aladdin.voicecore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aladdin.voicecore.audio.AudioFocusManager
import com.aladdin.voicecore.audio.AudioPipeline
import com.aladdin.voicecore.engine.ContinuousListeningManager
import com.aladdin.voicecore.engine.WakeWordEngine
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import com.aladdin.voicecore.models.VoiceCoreState
import com.aladdin.voicecore.speech.STTEngine
import com.aladdin.voicecore.speech.TTSEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * VoiceCoreService — Foreground service that keeps the Voice Core alive.
 *
 * Bind to this service from your Activity/Fragment:
 *
 * ```kotlin
 * val intent = Intent(this, VoiceCoreService::class.java)
 * bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
 * ```
 *
 * Then call [VoiceCoreService.speak] to synthesize and [events] to observe all events.
 *
 * The service uses a PARTIAL_WAKE_LOCK so the CPU stays alive while the screen is off,
 * enabling zero-touch always-on operation.
 */
class VoiceCoreService : Service() {

    companion object {
        private const val TAG = "VoiceCoreService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_core_channel"
        private const val CHANNEL_NAME = "Aladdin Voice Core"

        const val ACTION_START = "com.aladdin.voicecore.START"
        const val ACTION_STOP  = "com.aladdin.voicecore.STOP"
        const val ACTION_WAKE  = "com.aladdin.voicecore.WAKE"
        const val EXTRA_CONFIG = "com.aladdin.voicecore.CONFIG"

        fun startIntent(context: Context, config: VoiceCoreConfig? = null): Intent =
            Intent(context, VoiceCoreService::class.java).apply {
                action = ACTION_START
                config?.let { putExtra(EXTRA_CONFIG, it) }
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, VoiceCoreService::class.java).apply { action = ACTION_STOP }
    }

    // ─── Binder ───────────────────────────────────────────────────────────────

    inner class VoiceCoreBinder : Binder() {
        val service: VoiceCoreService get() = this@VoiceCoreService
    }

    private val binder = VoiceCoreBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ─── Components ───────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var config: VoiceCoreConfig
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var sttEngine: STTEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var clManager: ContinuousListeningManager

    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Observe all VoiceCore events. */
    val events: SharedFlow<VoiceCoreEvent> get() = clManager.events

    /** Observe the current state machine state. */
    val state: StateFlow<VoiceCoreState> get() = clManager.state

    /** Speak a response using Piper TTS. Interrupts any current speech. */
    fun speak(text: String) = clManager.speak(text)

    /** Interrupt current TTS playback (barge-in). */
    fun stopSpeaking() = ttsEngine.stop()

    /** Wake from sleep manually. */
    fun wakeUp() = clManager.wakeUp()

    /** Update wake word sensitivity at runtime (0.3 – 0.9). */
    fun setSensitivity(value: Float) = wakeWordEngine.updateSensitivity(value)

    /** Toggle conversation mode (stay in listening state after TTS). */
    fun setConversationMode(enabled: Boolean) { clManager.conversationMode = enabled }

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "VoiceCoreService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKE -> wakeUp()
        }

        val cfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CONFIG, VoiceCoreConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CONFIG)
        } ?: VoiceCoreConfig()

        if (!::config.isInitialized) {
            config = cfg
            startForeground(NOTIFICATION_ID, buildNotification("Listening…"))
            acquireWakeLock()
            initComponents()
            startComponents()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VoiceCoreService destroying")
        stopComponents()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Initialisation ───────────────────────────────────────────────────────

    private fun initComponents() {
        audioFocusManager = AudioFocusManager(this)

        audioPipeline = AudioPipeline(this, config)

        wakeWordEngine = WakeWordEngine(
            context = this,
            config = config,
            audioFrames = audioPipeline.audioFrames
        )

        sttEngine = STTEngine(
            context = this,
            config = config,
            audioFrames = audioPipeline.audioFrames
        ).also { it.init() }

        ttsEngine = TTSEngine(
            context = this,
            config = config,
            audioFocusManager = audioFocusManager
        )

        clManager = ContinuousListeningManager(
            config = config,
            audioPipeline = audioPipeline,
            wakeWordEngine = wakeWordEngine,
            sttEngine = sttEngine,
            ttsEngine = ttsEngine,
            externalScope = serviceScope
        )

        // Mirror state to notification
        serviceScope.launch {
            clManager.state.collect { state ->
                val label = when (state) {
                    VoiceCoreState.SLEEP -> "Sleeping…"
                    VoiceCoreState.LISTENING_FOR_WAKE_WORD -> "Listening…"
                    VoiceCoreState.CAPTURING_SPEECH -> "Capturing speech…"
                    VoiceCoreState.PROCESSING_STT -> "Processing…"
                    VoiceCoreState.SPEAKING_TTS -> "Speaking…"
                    VoiceCoreState.ERROR -> "Error"
                    VoiceCoreState.IDLE -> "Idle"
                }
                updateNotification(label)
            }
        }

        Log.i(TAG, "Components initialised with config: $config")
    }

    private fun startComponents() {
        audioPipeline.start()
        wakeWordEngine.start()
        clManager.start()
        Log.i(TAG, "Components started")
    }

    private fun stopComponents() {
        clManager.stop()
        wakeWordEngine.stop()
        sttEngine.shutdown()
        ttsEngine.shutdown()
        audioPipeline.stop()
        Log.i(TAG, "Components stopped")
    }

    // ─── Wake Lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceCore::WakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24h max, will be released in onDestroy
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.apply {
            if (isHeld) release()
        }
        wakeLock = null
        Log.i(TAG, "WakeLock released")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Aladdin Voice Core alive"
            setSound(null, null)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aladdin Voice Core")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
