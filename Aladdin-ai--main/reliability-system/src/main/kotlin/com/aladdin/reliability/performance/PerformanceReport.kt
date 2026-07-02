package com.aladdin.reliability.performance

import java.util.Date

data class PerformanceSample(
    val timestampMs: Long = System.currentTimeMillis(),
    val cpuPercent: Float,
    val memoryUsedMb: Long,
    val memoryTotalMb: Long,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val batteryTemperatureC: Float
)

data class PerformanceReport(
    val generatedAtMs: Long = System.currentTimeMillis(),
    val samples: List<PerformanceSample>,
    val durationMs: Long
) {
    val avgCpu    get() = samples.map { it.cpuPercent }.average().toFloat()
    val maxCpu    get() = samples.maxOfOrNull { it.cpuPercent } ?: 0f
    val avgMemMb  get() = samples.map { it.memoryUsedMb }.average().toLong()
    val maxMemMb  get() = samples.maxOfOrNull { it.memoryUsedMb } ?: 0L
    val avgBattery get() = samples.map { it.batteryPercent }.average().toInt()

    fun toMarkdown(): String = buildString {
        appendLine("# Performance Report")
        appendLine("**Generated:** ${Date(generatedAtMs)}")
        appendLine("**Duration:** ${durationMs / 1000}s  |  **Samples:** ${samples.size}")
        appendLine()
        appendLine("## Summary")
        appendLine("| Metric | Average | Peak |")
        appendLine("|--------|---------|------|")
        appendLine("| CPU | ${"%.1f".format(avgCpu)}% | ${"%.1f".format(maxCpu)}% |")
        appendLine("| Memory | ${avgMemMb}MB | ${maxMemMb}MB |")
        appendLine("| Battery | ${avgBattery}% | — |")
        appendLine()
        if (samples.isNotEmpty()) {
            appendLine("## Sample Timeline (last 10)")
            appendLine("| Time | CPU% | Mem MB | Battery% | Temp°C |")
            appendLine("|------|------|--------|----------|--------|")
            samples.takeLast(10).forEach { s ->
                appendLine("| ${Date(s.timestampMs)} | ${"%.1f".format(s.cpuPercent)} | ${s.memoryUsedMb} | ${s.batteryPercent} | ${"%.1f".format(s.batteryTemperatureC)} |")
            }
        }
    }
}
