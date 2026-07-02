package com.aladdin.performance.memory

import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight memory-leak detector.
 * Track objects with WeakReference; if they're still reachable after GC, log a warning.
 * Does NOT replace LeakCanary — use this for production sampling.
 */
class MemoryLeakDetector {

    companion object { private const val TAG = "MemoryLeakDetector" }

    private data class Tracked(val name: String, val ref: WeakReference<Any>, val trackedAtMs: Long)
    private val tracked = ConcurrentHashMap<String, Tracked>()

    fun track(obj: Any, name: String = obj.javaClass.simpleName): String {
        val id = "${name}_${System.nanoTime()}"
        tracked[id] = Tracked(name, WeakReference(obj), System.currentTimeMillis())
        return id
    }

    fun release(id: String) { tracked.remove(id) }

    /**
     * Suggest a GC then check which tracked objects are still alive.
     * Objects alive > [thresholdMs] ms after tracking are reported as potential leaks.
     */
    fun scan(thresholdMs: Long = 30_000): List<String> {
        System.gc()
        Thread.sleep(100)
        val now = System.currentTimeMillis()
        val leaks = mutableListOf<String>()
        val stale = mutableListOf<String>()

        tracked.forEach { (id, entry) ->
            if (entry.ref.get() != null) {
                val age = now - entry.trackedAtMs
                if (age > thresholdMs) {
                    leaks.add("${entry.name} (age=${age/1000}s)")
                    Log.w(TAG, "Potential leak: ${entry.name} still alive after ${age/1000}s")
                }
            } else {
                stale.add(id)
            }
        }
        stale.forEach { tracked.remove(it) }
        Log.i(TAG, "Leak scan: ${leaks.size} suspects, ${stale.size} GC'd, ${tracked.size} tracked")
        return leaks
    }

    fun clear() = tracked.clear()
    fun trackedCount() = tracked.size
}
