package com.aladdin.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * AndroidTTSWrapper — Special Item 27: Android TextToSpeech fallback.
 *
 * Implementation:
 *  • Wraps Android TextToSpeech with coroutine-friendly API
 *  • Auto-switch: used seamlessly when Piper is unavailable
 *  • speak()            — plays speech through the speaker
 *  • synthesizeToFile() — writes WAV/MP3 to a File
 *  • Proper engine cleanup via release()
 *  • Utterance IDs tracked to detect completion / error
 *  • setSpeechRate() / setPitch() wired through to TextToSpeech
 */
class AndroidTTSWrapper(private val context: Context) {

    companion object {
        private const val TAG          = "AndroidTTSWrapper"
        private const val INIT_TIMEOUT = 5L   // seconds
    }

    private var tts: TextToSpeech? = null
    private val utteranceCounter   = AtomicInteger(0)

    /**
     * Whether the TextToSpeech engine initialised successfully.
     */
    @Volatile
    var isReady: Boolean = false
        private set

    // ── Initialise ────────────────────────────────────────────────────────────

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isReady) return@withContext true
        val latch = CountDownLatch(1)
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                          result != TextToSpeech.LANG_NOT_SUPPORTED
                if (isReady) Log.i(TAG, "Android TTS initialised (Locale.US)")
                else         Log.w(TAG, "Android TTS: language data missing — trying default locale")
                if (!isReady) {
                    tts?.setLanguage(Locale.getDefault())
                    isReady = true
                }
            } else {
                Log.e(TAG, "Android TTS init failed: status=$status")
                isReady = false
            }
            latch.countDown()
        }
        latch.await(INIT_TIMEOUT, TimeUnit.SECONDS)
        isReady
    }

    // ── Speak ─────────────────────────────────────────────────────────────────

    /**
     * Speak [text] through the device speaker.
     * Suspends until the utterance is complete or errors out.
     */
    suspend fun speak(text: String): Boolean {
        if (!isReady) initialize()
        val engine = tts ?: return false
        val uid    = "aladdin_speak_${utteranceCounter.incrementAndGet()}"

        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == uid) cont.resume(true)
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == uid) {
                        Log.e(TAG, "TTS utterance error: $utteranceId")
                        cont.resume(false)
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == uid) {
                        Log.e(TAG, "TTS utterance error: $utteranceId code=$errorCode")
                        cont.resume(false)
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS.speak() returned error: $result")
                cont.resume(false)
            }

            cont.invokeOnCancellation { engine.stop() }
        }
    }

    // ── Synthesise to file ────────────────────────────────────────────────────

    /**
     * Write synthesis of [text] to [outputFile] (WAV format).
     * Suspends until writing is complete or errors out.
     */
    suspend fun synthesizeToFile(text: String, outputFile: File): Boolean {
        if (!isReady) initialize()
        val engine = tts ?: return false
        val uid    = "aladdin_file_${utteranceCounter.incrementAndGet()}"

        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == uid) {
                        Log.d(TAG, "Wrote TTS to file: ${outputFile.name}")
                        cont.resume(true)
                    }
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == uid) {
                        Log.e(TAG, "TTS file synthesis error: $utteranceId")
                        cont.resume(false)
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == uid) {
                        Log.e(TAG, "TTS file synthesis error: code=$errorCode")
                        cont.resume(false)
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
            }
            val result = engine.synthesizeToFile(text, params, outputFile, uid)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS.synthesizeToFile() returned error: $result")
                cont.resume(false)
            }

            cont.invokeOnCancellation { engine.stop() }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.1f, 4.0f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.1f, 4.0f))
    }

    fun stop() {
        tts?.stop()
    }

    // ── Release ───────────────────────────────────────────────────────────────

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts    = null
        isReady = false
        Log.d(TAG, "Android TTS released")
    }
}
