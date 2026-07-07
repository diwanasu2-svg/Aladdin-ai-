package com.aladdin.pipertts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin façade over the Piper JNI bridge (piper_bridge.cpp / libpiper_bridge.so).
 *
 * Synthesizes speech fully on-device from a local ONNX voice model — default
 * voice is the **male** "en_US-ryan-medium" — and plays it directly through
 * an [AudioTrack]. No subprocess, no network access.
 */
class PiperTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "PiperTtsEngine"
        const val DEFAULT_MALE_VOICE = "en_US-ryan-medium"

        @Volatile private var libraryLoaded = false
        private fun ensureLibraryLoaded(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("piper_bridge")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libpiper_bridge.so not found (build the :piper-tts module's " +
                        "native target first): ${e.message}")
                false
            }
        }
    }

    private var handle: Long = 0L
    @Volatile var isReady: Boolean = false
        private set
    private var sampleRate: Int = 22_050
    private var audioTrack: AudioTrack? = null

    fun voiceModelPath(voice: String = DEFAULT_MALE_VOICE) =
        File(context.filesDir, "models/piper/$voice.onnx").absolutePath

    fun voiceConfigPath(voice: String = DEFAULT_MALE_VOICE) =
        File(context.filesDir, "models/piper/$voice.onnx.json").absolutePath

    /** espeak-ng-data is bundled as an app asset and copied to filesDir on first run. */
    fun espeakDataPath() = File(context.filesDir, "models/piper/espeak-ng-data").absolutePath

    /**
     * Loads the male voice model. Call once, off the main thread, before speak().
     */
    suspend fun init(
        voice: String = DEFAULT_MALE_VOICE,
        modelPath: String = voiceModelPath(voice),
        configPath: String = voiceConfigPath(voice),
        espeakDataPath: String = espeakDataPath()
    ): Boolean = withContext(Dispatchers.Default) {
        if (!ensureLibraryLoaded()) return@withContext false
        if (!File(modelPath).exists() || !File(configPath).exists()) {
            Log.e(TAG, "Piper voice files not found for '$voice' — run ModelDownloader first")
            return@withContext false
        }
        handle = nativeInit(modelPath, configPath, espeakDataPath)
        isReady = handle != 0L
        if (isReady) {
            sampleRate = nativeSampleRate(handle)
            Log.i(TAG, "Piper ready with male voice '$voice' @ ${sampleRate}Hz")
        } else {
            Log.e(TAG, "Piper init failed for voice '$voice'")
        }
        isReady
    }

    /** Synthesizes [text] and plays it immediately through the device speaker. */
    suspend fun speak(text: String, speakingRate: Float = 1.0f) = withContext(Dispatchers.Default) {
        if (!isReady || text.isBlank()) return@withContext
        val lengthScale = 1.0f / speakingRate.coerceIn(0.25f, 4.0f)
        val pcm = nativeSynthesize(handle, text, lengthScale)
        if (pcm.isEmpty()) return@withContext
        playPcm(pcm)
    }

    /** Synthesizes [text] and returns raw 16-bit PCM samples without playing them. */
    suspend fun synthesizeToPcm(text: String, speakingRate: Float = 1.0f): ShortArray =
        withContext(Dispatchers.Default) {
            if (!isReady || text.isBlank()) return@withContext ShortArray(0)
            nativeSynthesize(handle, text, 1.0f / speakingRate.coerceIn(0.25f, 4.0f))
        }

    fun stop() {
        audioTrack?.apply { try { pause(); flush() } catch (_: Exception) {} }
    }

    fun shutdown() {
        stop()
        audioTrack?.release()
        audioTrack = null
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
        isReady = false
    }

    private fun playPcm(pcm: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = audioTrack ?: AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuf, pcm.size * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).also { audioTrack = it }

        track.play()
        track.write(pcm, 0, pcm.size)
    }

    // ─── JNI ──────────────────────────────────────────────────────────────────
    private external fun nativeInit(modelPath: String, configPath: String, espeakDataPath: String): Long
    private external fun nativeSynthesize(handle: Long, text: String, lengthScale: Float): ShortArray
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFree(handle: Long)
}
