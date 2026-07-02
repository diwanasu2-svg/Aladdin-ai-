package com.aladdin.performance.async

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*

/**
 * Utilities for streaming data through Kotlin Flows without blocking the UI thread.
 */
object StreamingFlow {

    /**
     * Streams token-by-token LLM output.
     * Emits each token on [Dispatchers.Default], collectable on any dispatcher.
     */
    fun tokenStream(tokens: List<String>, delayMs: Long = 0): Flow<String> = flow {
        tokens.forEach { token ->
            emit(token)
            if (delayMs > 0) delay(delayMs)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Streams audio chunks (PCM) from an audio source.
     * Automatically transforms chunks on the Default dispatcher.
     */
    fun <T> audioStream(
        source: suspend () -> T?,
        stopCondition: (T) -> Boolean = { false }
    ): Flow<T> = flow {
        while (true) {
            val chunk = source() ?: break
            emit(chunk)
            if (stopCondition(chunk)) break
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Merges multiple Flows into a single stream (e.g. multiple mic inputs).
     */
    fun <T> merge(vararg flows: Flow<T>): Flow<T> = merge(*flows)

    /**
     * Buffers a Flow up to [capacity] items — prevents slow consumers from stalling producers.
     */
    fun <T> Flow<T>.withBuffer(capacity: Int = 64): Flow<T> = buffer(capacity)

    /**
     * Debounces a Flow — only emits after [waitMs] of silence (e.g. stop-word detection).
     */
    fun <T> Flow<T>.debounce(waitMs: Long): Flow<T> = debounce(waitMs)

    /**
     * Conflates a Flow — drops intermediate items when consumer is slow (e.g. live transcription).
     */
    fun <T> Flow<T>.conflated(): Flow<T> = conflate()
}
