package com.aladdin.app.jarvis

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aladdin.app.audio.BackgroundMicCapture
import com.aladdin.app.conversation.BargeinHandler
import com.aladdin.app.conversation.ConversationManager
import com.aladdin.app.conversation.ConversationState
import com.aladdin.app.notification.JarvisMediaNotification
import com.aladdin.app.notification.NotificationHelper
import com.aladdin.app.overlay.OverlayManager
import com.aladdin.app.receiver.JarvisActionReceiver
import com.aladdin.app.receiver.ScreenStateReceiver
import com.aladdin.app.wakeword.WakeEvent
import com.aladdin.app.wakeword.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.*
import javax.inject.Inject

private const val TAG = "JarvisService"
private const val WAKE_LOCK_TAG        = "Aladdin:JarvisWakeLock"
private const val NOTIF_ID             = 2001
private const val CRASH_RESTART_DELAY  = 3_000L   // ms before self-restart after crash

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║                        J A R V I S  S E R V I C E                   ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  The always-on brain of Aladdin. Orchestrates every Jarvis feature:  ║
 * ║                                                                       ║
 * ║  ① ALWAYS RUNNING   — START_STICKY + uncaughtException restart        ║
 * ║  ② WAKE LOCK        — PARTIAL_WAKE_LOCK keeps CPU alive screen-off    ║
 * ║  ③ SCREEN-OFF MIC   — BackgroundMicCapture + AudioRingBuffer          ║
 * ║  ④ WAKE WORD        — WakeWordDetector fires WakeEvent on "Aladdin"   ║
 * ║  ⑤ LOCK SCREEN      — LockScreenOverlay via SYSTEM_ALERT_WINDOW       ║
 * ║  ⑥ CONVERSATION     — ConversationManager multi-turn context          ║
 * ║  ⑦ BARGE-IN         — BargeinHandler stops TTS on user speech         ║
 * ║  ⑧ MEDIA NOTIF      — JarvisMediaNotification with 3 action buttons   ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
@AndroidEntryPoint
class JarvisService : LifecycleService() {

    // ─── Injected components ──────────────────────────────────────────────────
    @Inject lateinit var micCapture: BackgroundMicCapture
    @Inject lateinit var wakeWordDetector: WakeWordDetector
    @Inject lateinit var conversationManager: ConversationManager
    @Inject lateinit var bargeinHandler: BargeinHandler
    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var jarvisNotification: JarvisMediaNotification

    // ─── Internal state ───────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var mediaSession: android.support.v4.media.session.MediaSessionCompat? = null
    private val screenReceiver = ScreenStateReceiver()
    private var isScreenOff = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─── Action constants ─────────────────────────────────────────────────────
    companion object {
        const val ACTION_START           = "com.aladdin.jarvis.START"
        const val ACTION_STOP            = "com.aladdin.jarvis.STOP"
        const val ACTION_START_LISTENING = "com.aladdin.jarvis.START_LISTENING"
        const val ACTION_PAUSE_TTS       = "com.aladdin.jarvis.PAUSE_TTS"
        const val ACTION_RESUME_TTS      = "com.aladdin.jarvis.RESUME_TTS"

        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(
            context, Intent(context, JarvisService::class.java).setAction(ACTION_START)
        )
        fun stop(context: Context) = context.startService(
            Intent(context, JarvisService::class.java).setAction(ACTION_STOP)
        )
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate — installing crash restarter")
        installCrashRestarter()
        acquireWakeLock()
        initTts()
        registerScreenReceiver()
        observeConversationState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(); return START_NOT_STICKY
            }
            ACTION_START_LISTENING -> conversationManager.startSession()
            ACTION_PAUSE_TTS       -> tts?.playSilence(0, TextToSpeech.QUEUE_FLUSH, null, "silence")
            ACTION_RESUME_TTS      -> { /* TTS resumes automatically */ }
            else -> bootUp()  // ACTION_START or null (system restart)
        }

        return START_STICKY   // ← system WILL restart us if killed
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        serviceScope.cancel()
        stopMicAndWakeWord()
        overlayManager.hide()
        releaseWakeLock()
        tts?.shutdown()
        mediaSession?.release()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    // ─── Boot sequence ────────────────────────────────────────────────────────

    private fun bootUp() {
        mediaSession = jarvisNotification.initMediaSession()
        startForeground(NOTIF_ID, buildNotification("Ready — say \"Aladdin\""))
        startMicAndWakeWord()
        scheduleIdleCheck()
        Log.d(TAG, "Jarvis online ✓")
    }

    // ─── Mic + Wake word ──────────────────────────────────────────────────────

    private fun startMicAndWakeWord() {
        micCapture.addListener(bargeinHandler::onAudioFrame)
        micCapture.start()
        wakeWordDetector.startListening()

        wakeWordDetector.wakeEvents
            .onEach { event -> onWakeWordDetected(event) }
            .launchIn(serviceScope)

        Log.d(TAG, "Mic + wake-word active")
    }

    private fun stopMicAndWakeWord() {
        wakeWordDetector.stopListening()
        micCapture.removeListener(bargeinHandler::onAudioFrame)
        micCapture.stop()
    }

    // ─── Wake word handler ────────────────────────────────────────────────────

    private fun onWakeWordDetected(event: WakeEvent) {
        Log.d(TAG, "Wake word '${event.keyword}' (conf=${event.confidence})")
        if (isScreenOff) {
            // Show lock-screen overlay and start conversation
            showLockScreenOverlay()
        }
        conversationManager.startSession()
        speakResponse("Yes, how can I help?")
    }

    // ─── Screen on/off ────────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        screenReceiver.onScreenOff = {
            isScreenOff = true
            Log.d(TAG, "Screen off — mic stays active")
            // PARTIAL_WAKE_LOCK is already held; mic keeps running
        }
        screenReceiver.onScreenOn = {
            isScreenOff = false
            Log.d(TAG, "Screen on")
            overlayManager.hide()
        }
        screenReceiver.onUserPresent = {
            isScreenOff = false
            overlayManager.hide()
        }
        registerReceiver(screenReceiver, ScreenStateReceiver.intentFilter())
    }

    // ─── Lock screen overlay ──────────────────────────────────────────────────

    private fun showLockScreenOverlay() {
        overlayManager.showOnLockScreen(
            onMicClicked = { conversationManager.startSession() },
            onDismissed  = { conversationManager.endSession() }
        )
    }

    // ─── TTS ──────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        conversationManager.onSpeechFinished()
                        updateNotification(buildNotification("Listening…"))
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                bargeinHandler.attachTts(tts!!)
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speakResponse(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aladdin_${System.currentTimeMillis()}")
        updateNotification(buildNotification("Speaking: ${text.take(60)}…"))
    }

    // ─── Conversation state observer ──────────────────────────────────────────

    private fun observeConversationState() {
        conversationManager.currentState
            .onEach { state ->
                val statusText = when (state) {
                    is ConversationState.Idle     -> "Say \"Aladdin\" to wake"
                    is ConversationState.Listening -> "Listening…"
                    is ConversationState.Thinking  -> "Thinking…"
                    is ConversationState.Speaking  -> "Speaking…"
                    is ConversationState.Error     -> "Error: ${state.message}"
                }
                updateNotification(buildNotification(statusText, state is ConversationState.Speaking))
            }
            .launchIn(lifecycleScope)
    }

    // ─── Idle check ───────────────────────────────────────────────────────────

    private fun scheduleIdleCheck() {
        serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                conversationManager.checkIdleTimeout()
            }
        }
    }

    // ─── Wake lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()           // indefinite — released only in onDestroy or shutdown
        }
        Log.d(TAG, "PARTIAL_WAKE_LOCK acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ─── Crash auto-restart ───────────────────────────────────────────────────

    private fun installCrashRestarter() {
        val appCtx = applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION in $thread — scheduling restart", throwable)
            serviceScope.launch {
                delay(CRASH_RESTART_DELAY)
                start(appCtx)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(
        statusText: String = "Active",
        isSpeaking: Boolean = false
    ): Notification = mediaSession?.let { session ->
        jarvisNotification.build(session, statusText, isSpeaking)
    } ?: run {
        // Fallback before media session initializes
        androidx.core.app.NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Aladdin Jarvis")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIF_ID, notification)
    }

    // ─── Shutdown ─────────────────────────────────────────────────────────────

    private fun shutdown() {
        Log.d(TAG, "Shutdown requested")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
