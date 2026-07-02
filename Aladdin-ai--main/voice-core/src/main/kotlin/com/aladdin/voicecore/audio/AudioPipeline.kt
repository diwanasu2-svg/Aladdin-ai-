package com.aladdin.voicecore.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aladdin.voicecore.models.ErrorCode
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Always-on audio capture pipeline.
 *
 * Responsibilities:
 *  - AudioRecord at 16kHz PCM mono
 *  - WebRTC VAD (30ms frames)
 *  - RNNoise noise suppression
 *  - WebRTC AEC / AGC
 *  - Headset plug/unplug device switching via restart signal
 *  - Automatic mic recovery on error
 *  - Emits raw 16kHz PCM frames via [audioFrames] channel
 */
class AudioPipeline(
    private val context: Context,
    private val config: VoiceCoreConfig
) {
    companion object {
        private const val TAG = "AudioPipeline"
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private const val RECOVERY_DELAY_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val audioFrames = Channel<ShortArray>(capacity = 64)

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<VoiceCoreEvent> = _events

    private var audioRecord: AudioRecord? = null
    private val frameSize: Int by lazy {
        (config.sampleRateHz * config.frameSizeMs) / 1000
    }
    private val bufferSize: Int by lazy {
        maxOf(
            AudioRecord.getMinBufferSize(config.sampleRateHz, CHANNEL, ENCODING),
            frameSize * 2 * 4
        )
    }

    private val vadProcessor = VADProcessor(config)
    private val noiseProcessor = NoiseProcessor(config)

    @Volatile private var isRunning = false
    @Volatile private var isMuted = false
    private var recoveryAttempts = 0

    /**
     * Conflated channel — sending to this triggers a device-switch restart.
     * CONFLATED means multiple rapid signals collapse into one pending restart.
     */
    private val restartSignal = Channel<String>(Channel.CONFLATED)

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Audio device changed, switching source")
                    handleDeviceChange(intent)
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        isRunning = true
        recoveryAttempts = 0
        registerHeadsetReceiver()
        vadProcessor.init()
        noiseProcessor.init()
        scope.launch { captureLoop() }
        if (BuildConfig.DEBUG) Log.i(TAG, "AudioPipeline started – $config")
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null
        vadProcessor.release()
        noiseProcessor.release()
        try { context.unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
        if (BuildConfig.DEBUG) Log.i(TAG, "AudioPipeline stopped")
    }

    fun mute() { isMuted = true }
    fun unmute() { isMuted = false }

    // ─── Capture Loop ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun captureLoop() {
        while (isRunning && scope.isActive) {
            try {
                audioRecord = createAudioRecord()
                audioRecord!!.startRecording()
                if (BuildConfig.DEBUG) Log.i(TAG, "Recording started, bufferSize=$bufferSize frameSize=$frameSize")
                recoveryAttempts = 0  // Issue 34: reset between successful starts

                val rawBuffer = ShortArray(frameSize)
                var shouldRestart = false

                while (isRunning && scope.isActive && !shouldRestart) {
                    // Check for device-change restart signal (non-blocking)
                    val deviceName = restartSignal.tryReceive().getOrNull()
                    if (deviceName != null) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "Device change restart for: $deviceName")
                        _events.emit(VoiceCoreEvent.AudioDeviceChanged(deviceName))
                        shouldRestart = true
                        break
                    }

                    val read = audioRecord!!.read(rawBuffer, 0, frameSize)
                    if (read <= 0) {
                        Log.w(TAG, "AudioRecord.read returned $read – attempting recovery")
                        break
                    }
                    if (isMuted) continue

                    val processed = processFrame(rawBuffer.copyOf(read))
                    if (processed != null) {
                        audioFrames.trySend(processed)
                    }
                }

                if (shouldRestart) {
                    // Clean stop before restarting for new device
                    audioRecord?.apply {
                        try { stop(); release() } catch (_: Exception) {}
                    }
                    audioRecord = null
                    continue  // Loop back to create new AudioRecord for new device
                }

            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}", e)
                _events.emit(VoiceCoreEvent.Error(ErrorCode.MIC_UNAVAILABLE, e.message ?: "Unknown mic error", e))
            } finally {
                audioRecord?.apply {
                    try { stop() } catch (_: Exception) {}
                    release()
                }
                audioRecord = null
            }

            if (!isRunning) break

            // Recovery with back-off
            recoveryAttempts++
            if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
                Log.e(TAG, "Max recovery attempts reached, giving up")
                _events.emit(VoiceCoreEvent.Error(ErrorCode.MIC_UNAVAILABLE, "Mic unrecoverable after $MAX_RECOVERY_ATTEMPTS attempts"))
                isRunning = false
                break
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "Attempting mic recovery #$recoveryAttempts in ${RECOVERY_DELAY_MS}ms")
            kotlinx.coroutines.delay(RECOVERY_DELAY_MS * recoveryAttempts)
            _events.emit(VoiceCoreEvent.MicRecovered)
        }
    }

    // ─── Processing Chain ─────────────────────────────────────────────────────

    private fun processFrame(raw: ShortArray): ShortArray? {
        var frame = raw
        if (config.enableNoiseSuppression) {
            frame = noiseProcessor.process(frame) ?: return null
        }
        if (config.enableAGC) {
            frame = applyAGC(frame)
        }
        if (config.enableVAD) {
            val hasSpeech = vadProcessor.isSpeech(frame)
            if (!hasSpeech) return null
        }
        return frame
    }

    private fun applyAGC(frame: ShortArray): ShortArray {
        val rms = Math.sqrt(frame.map { it.toDouble() * it.toDouble() }.average())
        val targetRms = 3000.0
        val gain = if (rms > 0) (targetRms / rms).coerceIn(0.1, 8.0) else 1.0
        return ShortArray(frame.size) { i ->
            (frame[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    // ─── AudioRecord Factory ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val source = if (config.enableAEC)
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else
            MediaRecorder.AudioSource.MIC
        return AudioRecord(source, config.sampleRateHz, CHANNEL, ENCODING, bufferSize)
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" } }
    }

    // ─── Device Switching ─────────────────────────────────────────────────────

    /**
     * Signal the capture loop to restart and pick up the new audio device.
     * Uses a conflated Channel so rapid device events collapse into one restart.
     */
    private fun handleDeviceChange(intent: Intent) {
        val deviceName = intent.getStringExtra("name") ?: "Unknown"
        if (isRunning) {
            restartSignal.trySend(deviceName) // non-blocking, conflated
            if (BuildConfig.DEBUG) Log.i(TAG, "Restart signal sent for device: $deviceName")
        }
    }

    private fun registerHeadsetReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(headsetReceiver, filter)
    }
}
