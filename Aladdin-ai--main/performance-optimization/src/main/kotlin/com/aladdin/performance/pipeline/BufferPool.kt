package com.aladdin.performance.pipeline

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * Pool of pre-allocated direct ByteBuffers — avoids heap allocation on the hot path.
 * Using direct (off-heap) buffers enables zero-copy transfer to native code (ONNX, GGUF).
 */
class BufferPool(
    val bufferSize: Int,
    capacity: Int = 16,
    private val name: String = "bufPool"
) {
    companion object { private const val TAG = "BufferPool" }

    private val pool = ArrayBlockingQueue<ByteBuffer>(capacity)

    init { repeat(capacity) { pool.offer(ByteBuffer.allocateDirect(bufferSize)) } }

    /** Acquire a cleared direct ByteBuffer; allocates a new one if pool is empty */
    fun acquire(): ByteBuffer =
        (pool.poll() ?: ByteBuffer.allocateDirect(bufferSize).also {
            Log.v(TAG, "$name pool empty — allocating new buffer")
        }).also { it.clear() }

    /** Return a buffer to the pool; silently drops if full */
    fun release(buf: ByteBuffer) {
        buf.clear()
        if (!pool.offer(buf)) Log.v(TAG, "$name: pool full, dropping buffer")
    }

    inline fun <R> use(block: (ByteBuffer) -> R): R {
        val buf = acquire()
        return try { block(buf) } finally { release(buf) }
    }

    fun available() = pool.size
}

/** Shared buffer pools for common pipeline stages */
object PipelineBuffers {
    /** 16 KB — audio PCM to GGUF bridge */
    val audio = BufferPool(16 * 1024, 32, "audio")

    /** 256 KB — model input tensors */
    val tensor = BufferPool(256 * 1024, 8, "tensor")

    /** 4 KB — text output / streaming tokens */
    val text = BufferPool(4 * 1024, 32, "text")
}
