package com.aladdin.performance.pipeline

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Batches items flowing through a pipeline stage.
 * Collects up to [maxBatchSize] items or waits [windowMs] and then emits a batch.
 * This amortises per-batch overhead (e.g. JNI call setup for ONNX inference).
 */
class StageBatcher<T>(
    private val name: String,
    private val maxBatchSize: Int = 16,
    private val windowMs: Long = 20L
) {
    companion object { private const val TAG = "StageBatcher" }

    private val input = Channel<T>(Channel.UNLIMITED)

    fun emit(item: T) { input.trySend(item) }

    /**
     * Returns a Flow of batches.  Collect on the ML dispatcher.
     */
    fun batchFlow(): Flow<List<T>> = flow {
        val buf = mutableListOf<T>()
        var deadline = System.currentTimeMillis() + windowMs

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val item = if (remaining > 0) {
                withTimeoutOrNull(remaining) { input.receive() }
            } else null

            if (item != null) {
                buf.add(item)
                if (buf.size >= maxBatchSize) {
                    Log.v(TAG, "$name emitting full batch (${buf.size})")
                    emit(buf.toList()); buf.clear(); deadline = System.currentTimeMillis() + windowMs
                }
            } else {
                if (buf.isNotEmpty()) {
                    Log.v(TAG, "$name emitting time-window batch (${buf.size})")
                    emit(buf.toList()); buf.clear()
                }
                deadline = System.currentTimeMillis() + windowMs
            }
        }
    }

    fun close() = input.close()
}
