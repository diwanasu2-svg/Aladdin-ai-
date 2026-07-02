package com.aladdin.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PiperTTSEngine — Special Item 26: Full Piper TTS synthesis pipeline.
 *
 * Implementation:
 *  • Loads Piper voice model from assets/models/piper/ or filesDir/models/piper/
 *  • Synthesises text via JNI (PiperJNI) or ProcessBuilder fallback
 *  • Streams audio output through AudioTrack (16-bit PCM, 22050 Hz, mono)
 *  • synthesizeToFile() writes .wav for offline use
 *  • Falls back to AndroidTTSWrapper when Piper is unavailable
 *  • Speed and pitch adjustable via setSpeed() / setPitch()
 */
@Singleton
class PiperTTSEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG            = "PiperTTSEngine"
        private const val MODEL_DIR      = "models/piper"
        private const val DEFAULT_MODEL  = "en_US-lessac-medium.onnx"
        private const val SAMPLE_RATE    = 22050
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
    }

    private var nativeHandle: Long = 0L
    private var speed: Float       = 1.0f
    private var pitch: Float       = 1.0f
    private val androidTts: AndroidTTSWrapper by lazy { AndroidTTSWrapper(context) }

    // ─── Initialisation ───────────────────────────────────────────────────────

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (nativeHandle != 0L) return@withContext true        // already init

        if (!PiperJNI.nativeAvailable) {
            Log.w(TAG, "Piper native library unavailable — using AndroidTTS fallback")
            return@withContext androidTts.initialize()
        }

        val (modelPath, configPath) = findModelFiles() ?: run {
            Log.w(TAG, "Piper model not found in $MODEL_DIR — using AndroidTTS fallback")
            return@withContext androidTts.initialize()
        }

        return@withContext try {
            nativeHandle = PiperJNI.safeInit(modelPath, configPath)
            if (nativeHandle != 0L) {
                PiperJNI.setSpeed(nativeHandle, speed)
                PiperJNI.setPitch(nativeHandle, pitch)
                Log.i(TAG, "Piper TTS initialised (handle=$nativeHandle)")
                true
            } else {
                Log.w(TAG, "Piper init returned handle=0 — falling back to AndroidTTS")
                androidTts.initialize()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Piper init exception: ${e.message}")
            androidTts.initialize()
        }
    }

    val isInitialized: Boolean
        get() = nativeHandle != 0L || androidTts.isReady

    // ─── Synthesis ────────────────────────────────────────────────────────────

    /**
     * Synthesise [text] and write PCM/WAV to [outputFile].
     * @return true on success
     */
    suspend fun synthesizeToFile(text: String, outputFile: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInitialized) initialize()

            when {
                nativeHandle != 0L -> {
                    val ok = PiperJNI.safeSynthesize(nativeHandle, text, outputFile.absolutePath)
                    Log.d(TAG, "synthesizeToFile: ok=$ok path=${outputFile.name}")
                    ok
                }
                else -> androidTts.synthesizeToFile(text, outputFile)
            }
        }

    /**
     * Synthesise [text] and play it immediately via AudioTrack.
     * Uses raw PCM bytes from JNI for lowest latency; falls back to Android TTS.
     */
    suspend fun speakNow(text: String) = withContext(Dispatchers.IO) {
        if (!isInitialized) initialize()

        if (nativeHandle != 0L) {
            val pcm = PiperJNI.safeSynthesizeToBytes(nativeHandle, text)
            if (pcm.isNotEmpty()) {
                playPcm(pcm)
                return@withContext
            }
        }
        // Fall back to Android TTS playback
        androidTts.speak(text)
    }

    // ─── AudioTrack PCM playback ──────────────────────────────────────────────

    private fun playPcm(pcm: ByteArray) {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val track  = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            val chunkSize = 4096
            var offset    = 0
            while (offset < pcm.size) {
                val end   = minOf(offset + chunkSize, pcm.size)
                val wrote  = track.write(pcm, offset, end - offset)
                if (wrote < 0) break
                offset += wrote
            }
            track.stop()
            Log.d(TAG, "AudioTrack playback complete (${pcm.size} bytes)")
        } finally {
            track.release()
        }
    }

    // ─── Speed / Pitch ────────────────────────────────────────────────────────

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2.0f)
        if (nativeHandle != 0L) PiperJNI.setSpeed(nativeHandle, speed)
        androidTts.setSpeechRate(speed)
    }

    fun setPitch(p: Float) {
        pitch = p.coerceIn(0.5f, 2.0f)
        if (nativeHandle != 0L) PiperJNI.setPitch(nativeHandle, pitch)
        androidTts.setPitch(pitch)
    }

    // ─── Model discovery ──────────────────────────────────────────────────────

    /** Returns (modelPath, configPath) or null if model files are not present. */
    private fun findModelFiles(): Pair<String, String>? {
        // 1. Check filesDir (downloaded models)
        val dir   = File(context.filesDir, MODEL_DIR)
        val model = File(dir, DEFAULT_MODEL)
        val cfg   = File(dir, "$DEFAULT_MODEL.json")
        if (model.exists() && cfg.exists()) return model.absolutePath to cfg.absolutePath

        // 2. Check assets/models/piper/ (bundled models)
        return try {
            val assetModel = context.assets.open("$MODEL_DIR/$DEFAULT_MODEL")
            val destModel  = File(dir.also { it.mkdirs() }, DEFAULT_MODEL)
            val assetCfg   = context.assets.open("$MODEL_DIR/$DEFAULT_MODEL.json")
            val destCfg    = File(dir, "$DEFAULT_MODEL.json")
            assetModel.use { input -> destModel.outputStream().use { input.copyTo(it) } }
            assetCfg.use   { input -> destCfg.outputStream().use  { input.copyTo(it) } }
            Log.i(TAG, "Copied bundled Piper model to ${destModel.absolutePath}")
            destModel.absolutePath to destCfg.absolutePath
        } catch (_: Exception) { null }
    }

    // ─── Release ─────────────────────────────────────────────────────────────

    fun release() {
        PiperJNI.safeFree(nativeHandle)
        nativeHandle = 0L
        androidTts.release()
        Log.d(TAG, "Released")
    }
}
