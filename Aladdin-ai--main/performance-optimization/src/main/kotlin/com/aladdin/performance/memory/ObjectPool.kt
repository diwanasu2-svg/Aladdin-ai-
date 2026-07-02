package com.aladdin.performance.memory

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generic lock-free object pool — avoids GC pressure from frequent short-lived allocations.
 * Typical use: audio PCM buffers, ByteArrays for network payloads, StringBuilder for log lines.
 */
class ObjectPool<T>(
    private val capacity: Int,
    private val factory: () -> T,
    private val reset: (T) -> Unit = {},
    private val name: String = "pool"
) {
    companion object { private const val TAG = "ObjectPool" }

    private val pool  = ArrayBlockingQueue<T>(capacity)
    private val total = AtomicInteger(0)
    private val hits  = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    init { repeat(capacity / 2) { pool.offer(factory().also { total.incrementAndGet() }) } }

    /** Borrow an object; creates a new one if pool is empty (never blocks). */
    fun acquire(): T {
        val obj = pool.poll()
        return if (obj != null) {
            hits.incrementAndGet(); obj
        } else {
            misses.incrementAndGet(); total.incrementAndGet(); factory()
        }
    }

    /** Return object to the pool; silently discards if pool is full. */
    fun release(obj: T) {
        reset(obj)
        if (!pool.offer(obj)) Log.v(TAG, "$name: pool full, discarding object")
    }

    inline fun <R> use(block: (T) -> R): R {
        val obj = acquire()
        return try { block(obj) } finally { release(obj) }
    }

    fun hitRate() = if (hits.get() + misses.get() == 0) 0f
        else hits.get().toFloat() / (hits.get() + misses.get())
    fun stats() = "Pool[$name] total=$total hits=${hits.get()} misses=${misses.get()} rate=${"%.1f".format(hitRate()*100)}%"
}

/** Pre-built pools for common buffer types */
object CommonPools {
    /** 4096-sample PCM short arrays for audio capture */
    val audioPcm = ObjectPool(
        capacity = 32,
        factory  = { ShortArray(4096) },
        reset    = { arr -> arr.fill(0) },
        name     = "audioPcm"
    )

    /** 64 KB byte arrays for network/disk I/O */
    val ioBuffer = ObjectPool(
        capacity = 16,
        factory  = { ByteArray(65536) },
        reset    = { arr -> arr.fill(0) },
        name     = "ioBuffer"
    )

    /** Reusable StringBuilders for log assembly */
    val stringBuilder = ObjectPool(
        capacity = 8,
        factory  = { StringBuilder(256) },
        reset    = { sb -> sb.clear() },
        name     = "stringBuilder"
    )
}
