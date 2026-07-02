package com.aladdin.performance.threading

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * All audio I/O runs on the high-priority audio thread pool.
 * Provides a bounded channel to stream PCM chunks without blocking the mic capture thread.
 */
class AudioDispatcher {

    companion object { private const val TAG = "AudioDispatcher" }

    private val scope = CoroutineScope(SupervisorJob() + ThreadPoolManager.audio)

    /** Channel capacity = 64 × 4096-byte PCM chunks ≈ 256 KB audio ring buffer */
    private val audioChannel = Channel<ShortArray>(capacity = 64)

    /** Submit a PCM chunk from any thread; never blocks the audio capture thread */
    fun submit(pcm: ShortArray) {
        if (!audioChannel.trySend(pcm).isSuccess) {
            Log.w(TAG, "Audio channel full — dropping chunk (${pcm.size * 2} bytes)")
        }
    }

    /** Consume the audio stream as a Flow on the audio dispatcher */
    fun audioFlow(): Flow<ShortArray> = flow {
        for (chunk in audioChannel) emit(chunk)
    }

    fun <T> runAudio(block: suspend CoroutineScope.() -> T): Deferred<T> =
        scope.async(block = block)

    fun cancel() { audioChannel.close(); scope.cancel() }
}
