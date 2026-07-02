package com.aladdin.reliability.health

data class HealthReport(
    val timestampMs: Long = System.currentTimeMillis(),
    val cpuPercent: Float,
    val memoryUsedMb: Long,
    val memoryTotalMb: Long,
    val networkReachable: Boolean,
    val micAvailable: Boolean,
    val overallHealthy: Boolean,
    val issues: List<String> = emptyList()
) {
    val memoryPercent: Float get() = if (memoryTotalMb > 0) memoryUsedMb * 100f / memoryTotalMb else 0f

    fun toSummary(): String = buildString {
        appendLine("=== Health Report [${java.util.Date(timestampMs)}] ===")
        appendLine("CPU        : ${"%.1f".format(cpuPercent)}%")
        appendLine("Memory     : ${memoryUsedMb}/${memoryTotalMb}MB (${"%.1f".format(memoryPercent)}%)")
        appendLine("Network    : ${if (networkReachable) "OK" else "UNREACHABLE"}")
        appendLine("Microphone : ${if (micAvailable) "OK" else "UNAVAILABLE"}")
        appendLine("Overall    : ${if (overallHealthy) "HEALTHY ✓" else "DEGRADED ✗"}")
        if (issues.isNotEmpty()) {
            appendLine("Issues     :")
            issues.forEach { appendLine("  • $it") }
        }
    }
}
