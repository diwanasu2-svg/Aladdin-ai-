package com.aladdin.app.messaging.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "VoiceReplyHelper"

/**
 * VoiceReplyHelper — converts a text response to an OGG/PCM audio file
 * using Android's built-in [TextToSpeech] engine.
 *
 * The resulting file can then be attached to a Telegram voice message,
 * WhatsApp audio, or Discord attachment.
 *
 * Usage:
 *   val file: File? = voiceReplyHelper.synthesizeToFile("Hello!")
 *   // upload file, then delete it
 *   file?.delete()
 *
 * Output format: .wav PCM (supported by all platforms for upload)
 */
@Singleton
class VoiceReplyHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init { initTts() }

    // ─── Init TTS ─────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.d(TAG, "TTS engine ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    // ─── Synthesize to file ────────────────────────────────────────────────────

    /**
     * Synthesizes [text] to a WAV file in the app's cache directory.
     * Returns the File on success, null on failure.
     *
     * This is a suspend function that waits for synthesis to complete.
     */
    suspend fun synthesizeToFile(text: String): File? {
        if (!ttsReady || tts == null) {
            Log.e(TAG, "TTS not ready")
            return null
        }
        val outFile = File(context.cacheDir, "voice_reply_${System.currentTimeMillis()}.wav")
        val utteranceId = "vr_${System.currentTimeMillis()}"

        return suspendCancellableCoroutine { continuation ->
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) {
                    if (uid == utteranceId) {
                        Log.d(TAG, "Synthesis complete: ${outFile.absolutePath}")
                        continuation.resume(if (outFile.exists() && outFile.length() > 0) outFile else null)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(uid: String?) {
                    if (uid == utteranceId) {
                        Log.e(TAG, "Synthesis error for $uid")
                        continuation.resume(null)
                    }
                }
                override fun onError(uid: String?, errorCode: Int) {
                    if (uid == utteranceId) {
                        Log.e(TAG, "Synthesis error $errorCode for $uid")
                        continuation.resume(null)
                    }
                }
            })

            val result = tts!!.synthesizeToFile(
                text,
                null,
                outFile,
                utteranceId
            )
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "synthesizeToFile returned $result")
                continuation.resume(null)
            }

            continuation.invokeOnCancellation { outFile.delete() }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
