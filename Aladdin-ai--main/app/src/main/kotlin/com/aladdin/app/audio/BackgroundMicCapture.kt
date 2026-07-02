package com.aladdin.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackgroundMicCapture"

/**
 * BackgroundMicCapture — continuously reads from the microphone into [AudioRingBuffer].
 *
 * Runs at 16 kHz / 16-bit mono — ideal for wake-word detectors (low CPU/battery).
 * The ring buffer holds 3 seconds of audio. The wake-word detector peeks the latest
 * 1–2 s without stopping the write stream.
 *
 * Lifecycle:
 *   start() → writes continuously until stop()
 *   stop()  → releases AudioRecord immediately
 *
 * Listeners registered via [addListener] receive every raw frame for further
 * processing (e.g. VAD, ASR streaming).
 */
@Singleton
class BackgroundMicCapture @Inject constructor(
    val ringBuffer: AudioRingBuffer
) {
    companion object {
        const val SAMPLE_RATE   = 16_000        // Hz
        const val CHANNEL_CFG   = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT  = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_MILLIS  = 20            // 20 ms frames → 320 samples
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MILLIS / 1000
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val listeners = mutableListOf<(ShortArray) -> Unit>()
    @Volatile private var running = false

    fun addListener(listener: (ShortArray) -> Unit) = synchronized(listeners) {
        listeners.add(listener)
    }
    fun removeListener(listener: (ShortArray) -> Unit) = synchronized(listeners) {
        listeners.remove(listener)
    }

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
        ).also { it.startRecording() }

        Log.d(TAG, "Microphone capture started (${SAMPLE_RATE} Hz)")

        captureJob = scope.launch {
            val frame = ShortArray(FRAME_SAMPLES)
            while (running && isActive) {
                val read = audioRecord?.read(frame, 0, FRAME_SAMPLES) ?: -1
                if (read > 0) {
                    ringBuffer.write(frame, 0, read)
                    val copy = frame.copyOf(read)
                    synchronized(listeners) { listeners.forEach { it(copy) } }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    delay(50)
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        captureJob?.cancel()
        captureJob = null
        audioRecord?.run { stop(); release() }
        audioRecord = null
        Log.d(TAG, "Microphone capture stopped")
    }
}
