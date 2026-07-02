package com.aladdin.reliability.diagnostics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PerformanceBenchmark {

    companion object { private const val TAG = "PerformanceBenchmark" }

    suspend fun runAll(): Map<String, BenchmarkResult> = withContext(Dispatchers.Default) {
        mapOf(
            "CPU Integer Ops"    to benchmarkCpuInt(),
            "CPU Float Ops"      to benchmarkCpuFloat(),
            "Memory Alloc"       to benchmarkMemoryAlloc(),
            "String Ops"         to benchmarkStringOps(),
            "File I/O"           to benchmarkFileIo()
        )
    }

    private fun benchmarkCpuInt(): BenchmarkResult {
        val t0 = System.nanoTime()
        var x = 0L
        repeat(1_000_000) { x += it * 3L - it / 2L }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val mops = 1_000.0 / ms
        return BenchmarkResult(mops, "Mops/s", rate(mops, 200.0, 50.0))
    }

    private fun benchmarkCpuFloat(): BenchmarkResult {
        val t0 = System.nanoTime()
        var x = 0.0
        repeat(1_000_000) { x += Math.sin(it * 0.001) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val mops = 1_000.0 / ms
        return BenchmarkResult(mops, "Mops/s", rate(mops, 100.0, 20.0))
    }

    private fun benchmarkMemoryAlloc(): BenchmarkResult {
        val t0 = System.nanoTime()
        repeat(10_000) { ByteArray(1024) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val mbps = 10_000.0 * 1024 / 1024 / 1024 / (ms / 1000.0)
        return BenchmarkResult(mbps, "GB/s alloc", rate(mbps, 1.0, 0.1))
    }

    private fun benchmarkStringOps(): BenchmarkResult {
        val t0 = System.nanoTime()
        var s = ""
        repeat(5_000) { s = "Item$it: ${s.takeLast(10)}" }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val kops = 5.0 / ms * 1000
        return BenchmarkResult(kops, "Kops/s", rate(kops, 50.0, 10.0))
    }

    private fun benchmarkFileIo(): BenchmarkResult {
        val t0 = System.nanoTime()
        val file = java.io.File.createTempFile("bench", ".tmp")
        try {
            val data = ByteArray(1024 * 1024) { it.toByte() }
            file.writeBytes(data)
            file.readBytes()
        } finally { file.delete() }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val mbps = 2.0 / (ms / 1000.0)
        return BenchmarkResult(mbps, "MB/s", rate(mbps, 50.0, 10.0))
    }

    private fun rate(value: Double, good: Double, poor: Double) = when {
        value >= good -> "GOOD"
        value >= poor -> "FAIR"
        else          -> "POOR"
    }
}
