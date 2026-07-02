package com.aladdin.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PiperTTS — Item 32: Piper Android TTS integration.
 * JNI: libpiper.so → real Piper ONNX inference.
 * Fallback: Android system TTS (automatic, silent).
 *
 * Required: {filesDir}/models/piper/en_US-lessac-medium.onnx
 *           {filesDir}/models/piper/en_US-lessac-medium.onnx.json
 */
@Singleton
class PiperTTS @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val TAG = "PiperTTS"
        private const val MODEL_DIR = "models/piper"
        private const val DEFAULT_MODEL = "en_US-lessac-medium.onnx"
        private var nativeAvailable = false
        init {
            nativeAvailable = try { System.loadLibrary("piper"); Log.i(TAG, "Piper native loaded"); true }
            catch (e: UnsatisfiedLinkError) { Log.w(TAG, "libpiper.so not found — system TTS fallback"); false }
        }
    }

    private external fun nativeInit(model: String, config: String): Long
    private external fun nativeSynthesize(h: Long, text: String, out: String): Boolean
    private external fun nativeSetSpeed(h: Long, speed: Float)
    private external fun nativeSetPitch(h: Long, pitch: Float)
    private external fun nativeFree(h: Long)

    private var handle = 0L
    private var systemTts: TextToSpeech? = null
    private var systemReady = false
    val isInitialized get() = handle != 0L || systemReady

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (nativeAvailable) initNative() else initSystem()
    }

    private fun initNative(): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        val model = File(dir, DEFAULT_MODEL)
        val cfg = File(dir, "\$DEFAULT_MODEL.json")
        if (!model.exists()) { Log.w(TAG, "Piper model missing — system fallback"); return initSystem() }
        return try { handle = nativeInit(model.absolutePath, cfg.absolutePath); (handle != 0L).also { Log.i(TAG, "Piper native init: \$it") } }
        catch (e: Exception) { Log.e(TAG, "Piper init failed: \${e.message}"); initSystem() }
    }

    private fun initSystem(): Boolean {
        val latch = CountDownLatch(1)
        systemTts = TextToSpeech(context) { s -> systemReady = s == TextToSpeech.SUCCESS; if (systemReady) systemTts?.language = Locale.US; latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return systemReady
    }

    suspend fun synthesizeToFile(text: String, out: File): Boolean = withContext(Dispatchers.IO) {
        if (handle != 0L) try { nativeSynthesize(handle, text, out.absolutePath) } catch (_: Exception) { false }
        else { val p = android.os.Bundle(); val id = "piper_\${System.currentTimeMillis()}"; p.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id); systemTts?.synthesizeToFile(text, p, out, id) == TextToSpeech.SUCCESS }
    }

    fun setSpeed(speed: Float) {
        if (handle != 0L) try { nativeSetSpeed(handle, speed.coerceIn(0.5f, 2f)) } catch (_: Exception) {}
        systemTts?.setSpeechRate(speed)
    }

    fun setPitch(pitch: Float) {
        if (handle != 0L) try { nativeSetPitch(handle, pitch.coerceIn(0.5f, 2f)) } catch (_: Exception) {}
        systemTts?.setPitch(pitch)
    }

    fun release() {
        if (handle != 0L) { try { nativeFree(handle) } catch (_: Exception) {}; handle = 0L }
        systemTts?.shutdown(); systemTts = null
    }
}