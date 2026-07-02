package com.aladdin.performance.manager

import android.content.Context
import android.util.Log
import com.aladdin.performance.async.AsyncProcessor
import com.aladdin.performance.benchmark.PerformanceBenchmarkReport
import com.aladdin.performance.benchmark.PerformanceBenchmarkSuite
import com.aladdin.performance.cache.DiskModelCache
import com.aladdin.performance.cache.EmbeddingModelCache
import com.aladdin.performance.cache.PiperModelCache
import com.aladdin.performance.cache.WhisperModelCache
import com.aladdin.performance.cpu.CpuOptimizer
import com.aladdin.performance.memory.LruCacheManager
import com.aladdin.performance.memory.MemoryLeakDetector
import com.aladdin.performance.memory.MemoryOptimizer
import com.aladdin.performance.pipeline.PipelineOptimizer
import com.aladdin.performance.startup.StartupOptimizer
import com.aladdin.performance.threading.ThreadPoolManager

/**
 * Top-level entry point for the Aladdin Performance Optimization System.
 *
 * Quick start:
 * ```kotlin
 * val perf = PerformanceOptimizationManager(context)
 * perf.start()  // Application.onCreate()
 *
 * // Later — run benchmarks to verify targets
 * val report = perf.runBenchmarks()
 * Log.i("Perf", report.toMarkdown())
 * ```
 *
 * Performance targets enforced:
 *  ● Startup  < 3 000 ms
 *  ● Avg CPU  < 10 %
 *  ● Peak RAM < 200 MB
 */
class PerformanceOptimizationManager(val context: Context) {

    companion object { private const val TAG = "PerfOptManager" }

    // ─── Model Caches ─────────────────────────────────────────────────────────
    val whisperCache   = WhisperModelCache(context)
    val piperCache     = PiperModelCache(context)
    val embeddingCache = EmbeddingModelCache(context)
    val diskCache      = DiskModelCache(context)

    // ─── Runtime Caches ───────────────────────────────────────────────────────
    val lruCaches = LruCacheManager()

    // ─── Subsystems ───────────────────────────────────────────────────────────
    val asyncProcessor  = AsyncProcessor()
    val memoryOptimizer = MemoryOptimizer(context, lruCaches)
    val cpuOptimizer    = CpuOptimizer(context)
    val startupOptimizer = StartupOptimizer(context)
    val leakDetector    = MemoryLeakDetector()
    val pipeline        = PipelineOptimizer()
    val benchmarkSuite  = PerformanceBenchmarkSuite(context)

    private var started = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Call from Application.onCreate().
     * Starts all monitoring subsystems and warms up critical resources.
     */
    suspend fun start() {
        if (started) return
        started = true
        Log.i(TAG, "PerformanceOptimizationManager starting...")

        startupOptimizer.tracker.begin()
        startupOptimizer.tracker.mark("PerfManager.start")

        // Memory monitoring (registers ComponentCallbacks2)
        memoryOptimizer.startMonitoring()
        startupOptimizer.tracker.mark("MemoryMonitor.start")

        // CPU monitoring (background coroutine)
        cpuOptimizer.startMonitoring()
        startupOptimizer.tracker.mark("CpuMonitor.start")

        startupOptimizer.tracker.mark("PerfManager.ready")
        Log.i(TAG, "PerformanceOptimizationManager started — RAM ${memoryOptimizer.heapUsageMb()}MB heap")
    }

    fun stop() {
        pipeline.stop()
        memoryOptimizer.stopMonitoring()
        cpuOptimizer.stopMonitoring()
        asyncProcessor.cancelAll()
        ThreadPoolManager.shutdown()
        Log.i(TAG, "PerformanceOptimizationManager stopped")
    }

    // ─── Benchmarks ───────────────────────────────────────────────────────────

    suspend fun runBenchmarks(): PerformanceBenchmarkReport {
        Log.i(TAG, "Running performance benchmarks...")
        return benchmarkSuite.runAll(startupMs = startupOptimizer.tracker.totalMs())
    }

    // ─── Quick Status ─────────────────────────────────────────────────────────

    fun status(): String = buildString {
        appendLine("=== Performance Status ===")
        appendLine("RAM (heap):  ${memoryOptimizer.heapUsageMb()}MB / ${memoryOptimizer.heapMaxMb()}MB")
        appendLine("CPU avg:     ${"%.1f".format(cpuOptimizer.rollingAvgCpu())}% (target < 10%)")
        appendLine("Startup:     ${startupOptimizer.tracker.totalMs()}ms (target < 3000ms)")
        appendLine("Pipeline:    ${if (pipeline.isRunning()) "running" else "stopped"}")
        appendLine("Active jobs: ${asyncProcessor.activeJobCount()}")
        appendLine("Leak watch:  ${leakDetector.trackedCount()} objects tracked")
        appendLine(lruCaches.stats())
    }

    fun meetsAllTargets(): Boolean {
        val ramOk     = memoryOptimizer.heapUsageMb() < 200
        val cpuOk     = !cpuOptimizer.isOverTarget()
        val startupOk = startupOptimizer.tracker.let { !it.metTarget() || it.totalMs() == 0L }
        return ramOk && cpuOk
    }
}
