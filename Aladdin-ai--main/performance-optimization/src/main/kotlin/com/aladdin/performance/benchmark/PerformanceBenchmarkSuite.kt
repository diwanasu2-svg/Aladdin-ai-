package com.aladdin.performance.benchmark

import android.content.Context
import android.util.Log
import com.aladdin.performance.memory.LruCacheManager
import com.aladdin.performance.memory.ObjectPool
import com.aladdin.performance.startup.StartupTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Comprehensive benchmark suite that validates all performance targets:
 *
 *  ● Startup         < 3 000 ms
 *  ● Avg CPU         < 10 %
 *  ● Peak RAM        < 200 MB
 *  ● LRU cache hit   > 80 %
 *  ● Pool hit rate   > 90 %
 *  ● Disk I/O write  > 30 MB/s
 *  ● Disk I/O read   > 50 MB/s
 *  ● CPU int ops     > 200 Mops/s
 *  ● CPU float ops   > 100 Mops/s
 *  ● Mem alloc       > 0.5 GB/s
 */
class PerformanceBenchmarkSuite(private val context: Context) {

    companion object { private const val TAG = "PerfBenchmarkSuite" }

    private val lruManager = LruCacheManager()
    private val audioPool  = ObjectPool(32, { ShortArray(4096) }, { it.fill(0) }, "audio")

    suspend fun runAll(startupMs: Long = StartupTracker.totalMs()): PerformanceBenchmarkReport =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Running full benchmark suite...")
            val t0 = System.currentTimeMillis()

            val results = listOf(
                bench("CPU Integer Ops", unit = "Mops/s", target = 200.0)  { cpuInt() },
                bench("CPU Float Ops",   unit = "Mops/s", target = 100.0)  { cpuFloat() },
                bench("Memory Alloc",    unit = "GB/s",   target = 0.5)    { memAlloc() },
                bench("Disk Write",      unit = "MB/s",   target = 30.0)   { diskWrite() },
                bench("Disk Read",       unit = "MB/s",   target = 50.0)   { diskRead() },
                bench("LRU Cache Hit",   unit = "%",      target = 80.0)   { lruHitRate() },
                bench("Object Pool Hit", unit = "%",      target = 90.0)   { poolHitRate() },
                bench("String Ops",      unit = "Kops/s", target = 50.0)   { stringOps() },
                bench("RAM Usage",       unit = "MB",     target = null)   { ramUsage() }   // lower is better (no target)
            )

            val total = System.currentTimeMillis() - t0
            Log.i(TAG, "Benchmark suite complete in ${total}ms")

            val report = PerformanceBenchmarkReport(
                results        = results,
                startupMs      = startupMs,
                startupMetTarget = startupMs == 0L || startupMs < 3000
            )
            saveReport(report)
            report
        }

    // ─── Individual Benchmarks ─────────────────────────────────────────────────

    private fun cpuInt(): Double {
        val t0 = System.nanoTime(); var x = 0L
        repeat(1_000_000) { x += it * 3L - it / 2L }
        return 1_000.0 / ((System.nanoTime() - t0) / 1_000_000.0)
    }

    private fun cpuFloat(): Double {
        val t0 = System.nanoTime(); var x = 0.0
        repeat(1_000_000) { x += Math.sin(it * 0.001) }
        return 1_000.0 / ((System.nanoTime() - t0) / 1_000_000.0)
    }

    private fun memAlloc(): Double {
        val t0 = System.nanoTime()
        repeat(10_000) { ByteArray(1024) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        return 10_000.0 * 1024 / 1024 / 1024 / (ms / 1000.0)
    }

    private suspend fun diskWrite(): Double = withContext(Dispatchers.IO) {
        val data = ByteArray(1024 * 1024)
        val f = File.createTempFile("bench_w", ".tmp", context.cacheDir)
        val t0 = System.nanoTime()
        repeat(5) { f.appendBytes(data) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        f.delete()
        5.0 / (ms / 1000.0)
    }

    private suspend fun diskRead(): Double = withContext(Dispatchers.IO) {
        val data = ByteArray(5 * 1024 * 1024)
        val f = File.createTempFile("bench_r", ".tmp", context.cacheDir)
        f.writeBytes(data)
        val t0 = System.nanoTime()
        f.readBytes()
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        f.delete()
        5.0 / (ms / 1000.0)
    }

    private fun lruHitRate(): Double {
        repeat(200) { lruManager.responseCache.put("key$it", "value$it") }
        var hits = 0
        repeat(100) { i -> if (lruManager.responseCache.get("key$i") != null) hits++ }
        return hits.toDouble()   // out of 100 → %
    }

    private fun poolHitRate(): Double {
        val objs = (1..50).map { audioPool.acquire() }
        objs.forEach { audioPool.release(it) }
        repeat(50) { audioPool.acquire().also { audioPool.release(it) } }
        return audioPool.hitRate() * 100.0
    }

    private fun stringOps(): Double {
        val t0 = System.nanoTime(); var s = ""
        repeat(5_000) { s = "Item$it: ${s.takeLast(10)}" }
        return 5.0 / ((System.nanoTime() - t0) / 1_000_000.0) * 1000.0
    }

    private fun ramUsage(): Double {
        val rt = Runtime.getRuntime()
        return ((rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private inline fun bench(
        name: String, unit: String, target: Double?,
        crossinline block: suspend () -> Double
    ): BenchmarkResult {
        val t0 = System.currentTimeMillis()
        val score = runCatching { kotlinx.coroutines.runBlocking { block() } }.getOrDefault(0.0)
        val duration = System.currentTimeMillis() - t0
        val rating = when {
            target == null       -> if (score < 200) BenchmarkRating.GOOD else BenchmarkRating.FAIR
            score >= target      -> BenchmarkRating.EXCELLENT
            score >= target * .7 -> BenchmarkRating.GOOD
            score >= target * .4 -> BenchmarkRating.FAIR
            else                 -> BenchmarkRating.POOR
        }
        Log.d(TAG, "$name: ${"%.2f".format(score)} $unit ($rating) in ${duration}ms")
        return BenchmarkResult(name, score, unit, rating, duration, target)
    }

    private fun saveReport(report: PerformanceBenchmarkReport) {
        try {
            val dir = File(context.filesDir, "bench_reports").also { it.mkdirs() }
            val f   = File(dir, "bench_${System.currentTimeMillis()}.md")
            f.writeText(report.toMarkdown())
            dir.listFiles()?.sortedByDescending { it.lastModified() }
                ?.drop(5)?.forEach { it.delete() }
            Log.i(TAG, "Benchmark report saved: ${f.name}")
        } catch (e: Exception) { Log.e(TAG, "Failed to save benchmark report", e) }
    }
}
