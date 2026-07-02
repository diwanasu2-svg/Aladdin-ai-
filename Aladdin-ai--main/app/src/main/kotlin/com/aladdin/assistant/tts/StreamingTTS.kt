package com.aladdin.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Locale
import java.util.UUID

/**
 * Phase 2 – Streaming LLM → TTS Pipeline
 *
 * Receives sentences from StreamingLLM one-by-one via [enqueueSentence].
 * Starts speaking the FIRST sentence immediately without waiting for the
 * full LLM response — Jarvis-style pipeline.
 *
 * Barge-in: [stopSpeaking] drains the queue and cancels TTS instantly.
 *
 * Bug fix (Item 40): The Channel was never recreated after [finishEnqueuing]
 * closed it, causing subsequent calls to [enqueueSentence] and
 * [resumeForNewUtterance] to silently fail on a closed channel.
 * Solution: recreate the Channel in [resumeForNewUtterance] before
 * launching the processor coroutine.
 */
class StreamingTTS(private val context: Context) {
    companion object { private const val TAG = "StreamingTTS" }

    interface SpeakingListener {
        fun onSpeakingStarted(sentence: String)
        fun onSpeakingFinished()
        fun onAllSentencesDone()
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Channel is recreated for each new utterance session to avoid the
    // "send on closed channel" crash that happened when resumeForNewUtterance
    // was called after finishEnqueuing had already closed the previous channel.
    private var sentenceQueue = Channel<String>(Channel.UNLIMITED)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var processorJob: Job? = null
    @Volatile private var stopped = false
    var speakingListener: SpeakingListener? = null

    fun initialise(onReady: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(0.95f)
                ttsReady = true
                Log.d(TAG, "TTS ready")
                onReady()
                launchProcessor()
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun enqueueSentence(sentence: String) {
        if (stopped || sentence.isBlank()) return
        val result = sentenceQueue.trySend(sentence.trim())
        if (result.isFailure) {
            Log.w(TAG, "Sentence queue full or closed — dropping: ${sentence.take(40)}")
        }
    }

    fun finishEnqueuing() {
        scope.launch { sentenceQueue.close() }
    }

    fun stopSpeaking() {
        stopped = true
        processorJob?.cancel()
        processorJob = null
        // Drain and close old channel without blocking
        sentenceQueue.cancel()
        tts?.stop()
        speakingListener?.onAllSentencesDone()
        Log.d(TAG, "TTS stopped and queue drained")
    }

    /**
     * Prepare for a new utterance session.
     * Must be called before enqueueSentence if the previous session was stopped
     * or finished (channel was closed).
     */
    fun resumeForNewUtterance() {
        stopped = false
        // Recreate the channel so new sentences can be enqueued on a fresh channel.
        sentenceQueue = Channel(Channel.UNLIMITED)
        launchProcessor()
        Log.d(TAG, "TTS resumed — new channel created")
    }

    fun release() {
        stopSpeaking()
        scope.cancel()
        tts?.shutdown()
        tts = null
    }

    private fun launchProcessor() {
        processorJob?.cancel()
        val channel = sentenceQueue  // capture current channel reference
        processorJob = scope.launch {
            for (sentence in channel) {
                if (stopped) break
                speakBlocking(sentence)
            }
            if (!stopped) speakingListener?.onAllSentencesDone()
        }
    }

    private suspend fun speakBlocking(sentence: String) {
        if (!ttsReady || tts == null) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {
                if (u == id) speakingListener?.onSpeakingStarted(sentence)
            }
            override fun onDone(u: String?) {
                if (u == id) {
                    speakingListener?.onSpeakingFinished()
                    done.complete(Unit)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(u: String?) {
                if (u == id) done.complete(Unit)
            }
        })
        tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, id)
        done.await()
    }
}
