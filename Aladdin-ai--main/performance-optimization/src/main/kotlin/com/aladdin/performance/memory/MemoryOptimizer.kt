package com.aladdin.performance.memory

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Monitors heap usage and reacts to ComponentCallbacks2 trim-memory signals.
 * Target: keep process RAM < 200 MB.
 *
 * Strategy by trim level:
 *   TRIM_MEMORY_RUNNING_LOW (10)  → trim LRU caches 50%
 *   TRIM_MEMORY_RUNNING_CRITICAL (15) → trim caches 80% + suggest GC
 *   TRIM_MEMORY_UI_HIDDEN (20)    → release audio/TTS buffers
 *   TRIM_MEMORY_COMPLETE (80)     → drop all non-essential caches
 */
class MemoryOptimizer(
    private val context: Context,
    private val lruManager: LruCacheManager,
    private val targetRamMb: Long = 200L
) : ComponentCallbacks2 {

    companion object { private const val TAG = "MemoryOptimizer" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // ─── ComponentCallbacks2 ──────────────────────────────────────────────────

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "onTrimMemory level=$level — ${trimLevelName(level)}")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE         -> emergencyTrim()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN        -> aggressiveTrim()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> criticalTrim()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW      -> lightTrim()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    override fun onLowMemory() { emergencyTrim(); Log.e(TAG, "onLowMemory — full cache evict") }

    // ─── Monitoring ───────────────────────────────────────────────────────────

    fun startMonitoring() {
        context.registerComponentCallbacks(this)
        scope.launch {
            while (true) {
                checkMemoryUsage()
                delay(30_000)
            }
        }
    }

    fun stopMonitoring() {
        context.unregisterComponentCallbacks(this)
        scope.cancel()
    }

    fun currentUsageMb(): Long {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem - info.availMem) / (1024 * 1024)
    }

    fun heapUsageMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    fun heapMaxMb() = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun checkMemoryUsage() {
        val heap = heapUsageMb()
        val max  = heapMaxMb()
        val pct  = heap * 100 / max
        when {
            pct >= 90 -> criticalTrim().also { Log.e(TAG, "CRITICAL heap: ${heap}MB/${max}MB ($pct%)") }
            pct >= 75 -> lightTrim().also    { Log.w(TAG, "HIGH heap: ${heap}MB/${max}MB ($pct%)") }
            else      -> Log.v(TAG, "Heap OK: ${heap}MB/${max}MB ($pct%)")
        }
    }

    private fun lightTrim() {
        lruManager.trimAll()
        Log.i(TAG, "Light trim complete. Heap: ${heapUsageMb()}MB")
    }

    private fun criticalTrim() {
        lruManager.evictAll()
        System.gc()
        Log.w(TAG, "Critical trim complete. Heap: ${heapUsageMb()}MB")
    }

    private fun aggressiveTrim() {
        lruManager.evictAll()
        CommonPools.audioPcm.let { Log.d(TAG, it.stats()) }
        System.gc()
        Log.w(TAG, "Aggressive trim. Heap: ${heapUsageMb()}MB")
    }

    private fun emergencyTrim() {
        lruManager.evictAll()
        System.gc()
        System.runFinalization()
        Log.e(TAG, "Emergency trim complete. Heap: ${heapUsageMb()}MB")
    }

    private fun trimLevelName(level: Int) = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE         -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE         -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND       -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN        -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW      -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        else                                             -> "LEVEL_$level"
    }
}
