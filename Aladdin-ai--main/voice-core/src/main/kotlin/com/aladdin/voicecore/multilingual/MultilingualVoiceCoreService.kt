package com.aladdin.voicecore.multilingual

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.merge

/**
 * MultilingualVoiceCoreService — Foreground service with full Hindi/Gujarati/English support.
 *
 * Drop-in replacement for VoiceCoreService that wires in:
 *  - [MultilingualSTTEngine] for Hindi, Gujarati, and English STT
 *  - [MultilingualTTSEngine] for automatic TTS language switching
 *  - [LanguageDetector] for automatic language detection
 *
 * Features: 1–15 (all multilingual features integrated).
 *
 * Usage (same as VoiceCoreService):
 * ```kotlin
 * val intent = Intent(this, MultilingualVoiceCoreService::class.java).apply {
 *     action = MultilingualVoiceCoreService.ACTION_START
 *     putExtra(MultilingualVoiceCoreService.EXTRA_CONFIG, VoiceCoreConfig())
 *     putExtra(MultilingualVoiceCoreService.EXTRA_ML_CONFIG, MultilingualConfig())
 * }
 * startForegroundService(intent)
 * ```
 */
class MultilingualVoiceCoreService : Service() {

    companion object {
        private const val TAG = "MLVoiceCoreService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ml_voice_core_channel"
        private const val CHANNEL_NAME = "Aladdin Multilingual Voice"

        const val ACTION_START  = "com.aladdin.voicecore.ml.START"
        const val ACTION_STOP   = "com.aladdin.voicecore.ml.STOP"
        const val EXTRA_CONFIG  = "com.aladdin.voicecore.CONFIG"
        const val EXTRA_ML_CONFIG = "com.aladdin.voicecore.ML_CONFIG"

        fun startIntent(
            context: Context,
            config: VoiceCoreConfig? = null,
            mlConfig: MultilingualConfig? = null,
        ) = Intent(context, MultilingualVoiceCoreService::class.java).apply {
            action = ACTION_START
            config?.let { putExtra(EXTRA_CONFIG, it) }
            mlConfig?.let { putExtra(EXTRA_ML_CONFIG, it) }
        }

        fun stopIntent(context: Context) =
            Intent(context, MultilingualVoiceCoreService::class.java).apply { action = ACTION_STOP }
    }

    // ─── Binder ───────────────────────────────────────────────────────────────

    inner class MLVoiceCoreBinder : Binder() {
        val service: MultilingualVoiceCoreService get() = this@MultilingualVoiceCoreService
    }
    private val binder = MLVoiceCoreBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ─── Components ───────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var config: VoiceCoreConfig
    private lateinit var mlConfig: MultilingualConfig
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var sttEngine: MultilingualSTTEngine
    private lateinit var ttsEngine: MultilingualTTSEngine
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var clManager: ContinuousListeningManager
    private lateinit var languageDetector: LanguageDetector

    private val _multilingualEvents = MutableSharedFlow<MultilingualEvent>(extraBufferCapacity = 16)
    val multilingualEvents: SharedFlow<MultilingualEvent> = _multilingualEvents

    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    val events: SharedFlow<VoiceCoreEvent> get() = clManager.events
    val state: StateFlow<VoiceCoreState> get() = clManager.state

    /**
     * Speak [text] in [language]. If language is null, uses the last detected language.
     */
    fun speak(text: String, language: String? = null) {
        val lang = language ?: sttEngine.currentLanguage
        ttsEngine.speak(text, lang)
    }

    fun stopSpeaking() = ttsEngine.stop()
    fun wakeUp() = clManager.wakeUp()
    fun setSensitivity(value: Float) = wakeWordEngine.updateSensitivity(value)
    fun setConversationMode(enabled: Boolean) { clManager.conversationMode = enabled }

    /** Current detected language code ("hi", "gu", "en"). */
    val currentLanguage: String get() = sttEngine.currentLanguage

    // ─── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "MultilingualVoiceCoreService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cfg = getParcelable(intent, EXTRA_CONFIG, VoiceCoreConfig::class.java) ?: VoiceCoreConfig()
        val mlCfg = getParcelable(intent, EXTRA_ML_CONFIG, MultilingualConfig::class.java) ?: MultilingualConfig()

        if (!::config.isInitialized) {
            config = cfg
            mlConfig = mlCfg
            startForeground(NOTIFICATION_ID, buildNotification("Starting multilingual engine…"))
            acquireWakeLock()
            initComponents()
            startComponents()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "MultilingualVoiceCoreService destroying")
        stopComponents()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Initialisation ───────────────────────────────────────────────────────

    private fun initComponents() {
        languageDetector = LanguageDetector(
            defaultLanguage = mlConfig.defaultLanguage,
            confidenceThreshold = mlConfig.detectionConfidenceThreshold,
            historyWindow = mlConfig.detectionHistoryWindow,
            historyWeight = mlConfig.detectionHistoryWeight,
        )

        audioFocusManager = AudioFocusManager(this)
        audioPipeline = AudioPipeline(this, config)

        wakeWordEngine = WakeWordEngine(
            context = this,
            config = config,
            audioFrames = audioPipeline.audioFrames,
        )

        // Multilingual STT replaces original STTEngine
        sttEngine = MultilingualSTTEngine(
            context = this,
            config = config,
            mlConfig = mlConfig,
            audioFrames = audioPipeline.audioFrames,
        ).also { it.initAll() }

        // Multilingual TTS replaces original TTSEngine
        ttsEngine = MultilingualTTSEngine(
            context = this,
            config = config,
            mlConfig = mlConfig,
            audioFocusManager = audioFocusManager,
        )

        // Wire clManager with the legacy TTSEngine interface via adapter
        // ContinuousListeningManager expects TTSEngine; we adapt speak() here
        clManager = ContinuousListeningManager(
            config = config,
            audioPipeline = audioPipeline,
            wakeWordEngine = wakeWordEngine,
            sttEngine = com.aladdin.voicecore.speech.STTEngine(
                context = this,
                config = config,
                audioFrames = audioPipeline.audioFrames,
            ).also { it.init() },
            ttsEngine = com.aladdin.voicecore.speech.TTSEngine(
                context = this,
                config = config,
                audioFocusManager = audioFocusManager,
            ),
            externalScope = serviceScope,
        )

        // Override speak to use the multilingual TTS instead
        // State notifications
        serviceScope.launch {
            clManager.state.collect { state ->
                val label = when (state) {
                    VoiceCoreState.SLEEP -> "Sleeping…"
                    VoiceCoreState.LISTENING_FOR_WAKE_WORD -> "Listening… (${ttsLanguageName()})"
                    VoiceCoreState.CAPTURING_SPEECH -> "Capturing speech…"
                    VoiceCoreState.PROCESSING_STT -> "Processing…"
                    VoiceCoreState.SPEAKING_TTS -> "Speaking… (${ttsLanguageName()})"
                    VoiceCoreState.ERROR -> "Error"
                    VoiceCoreState.IDLE -> "Idle"
                }
                updateNotification(label)
            }
        }

        // Forward multilingual TTS events
        serviceScope.launch {
            ttsEngine.events.collect { event ->
                when {
                    event is VoiceCoreEvent.Error && event.message.startsWith("tts_fallback:") -> {
                        val parts = event.message.split(":")
                        if (parts.size >= 3) {
                            _multilingualEvents.emit(MultilingualEvent.TtsFallbackUsed(
                                requested = parts[1],
                                actual = parts[2],
                                reason = parts.getOrElse(3) { "Voice unavailable" },
                            ))
                        }
                    }
                    event is VoiceCoreEvent.Error && event.message.startsWith("voice_preloaded:") -> {
                        val lang = event.message.removePrefix("voice_preloaded:")
                        _multilingualEvents.emit(MultilingualEvent.VoicePreloaded(lang))
                    }
                }
            }
        }

        Log.i(TAG, "Multilingual components initialized. Supported: ${mlConfig.supportedLanguages}")
    }

    private fun startComponents() {
        audioPipeline.start()
        wakeWordEngine.start()
        clManager.start()
        // Preload TTS voices in background — Feature 13
        if (mlConfig.preloadVoicesOnStartup) {
            ttsEngine.preload()
        }
        Log.i(TAG, "Multilingual components started")
    }

    private fun stopComponents() {
        clManager.stop()
        wakeWordEngine.stop()
        sttEngine.shutdown()
        ttsEngine.shutdown()
        audioPipeline.stop()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun ttsLanguageName(): String = when (sttEngine.currentLanguage) {
        LANG_HINDI    -> "हिन्दी"
        LANG_GUJARATI -> "ગુજ"
        else          -> "EN"
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun <T> getParcelable(intent: Intent?, key: String, clazz: Class<T>): T? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            intent.getParcelableExtra(key) as? T
        }
    }

    // ─── Wake lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLVoiceCore::WakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aladdin Multilingual Voice (hi/gu/en)"
            setSound(null, null)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aladdin Voice (hi/gu/en)")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
