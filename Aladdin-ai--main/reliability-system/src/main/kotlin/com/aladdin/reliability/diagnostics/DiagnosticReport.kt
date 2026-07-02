package com.aladdin.reliability.diagnostics

import java.util.Date

enum class DiagnosticStatus { PASS, WARN, FAIL }

data class DiagnosticResult(
    val name: String,
    val status: DiagnosticStatus,
    val message: String,
    val durationMs: Long = 0
)

data class DiagnosticReport(
    val timestampMs: Long = System.currentTimeMillis(),
    val results: List<DiagnosticResult>,
    val benchmarkResults: Map<String, BenchmarkResult> = emptyMap()
) {
    val passed  get() = results.count { it.status == DiagnosticStatus.PASS }
    val warned  get() = results.count { it.status == DiagnosticStatus.WARN }
    val failed  get() = results.count { it.status == DiagnosticStatus.FAIL }
    val healthy get() = failed == 0

    fun toMarkdown(): String = buildString {
        appendLine("# Aladdin Diagnostic Report")
        appendLine("**Generated:** ${Date(timestampMs)}")
        appendLine("**Result:** ${if (healthy) "✅ PASS ($passed passed, $warned warnings)" else "❌ FAIL ($failed failed, $warned warnings, $passed passed)"}")
        appendLine()
        appendLine("## Component Checks")
        appendLine("| Component | Status | Message | Duration |")
        appendLine("|-----------|--------|---------|----------|")
        results.forEach { r ->
            val icon = when (r.status) {
                DiagnosticStatus.PASS -> "✅"; DiagnosticStatus.WARN -> "⚠️"; DiagnosticStatus.FAIL -> "❌"
            }
            appendLine("| ${r.name} | $icon ${r.status} | ${r.message} | ${r.durationMs}ms |")
        }
        if (benchmarkResults.isNotEmpty()) {
            appendLine()
            appendLine("## Performance Benchmarks")
            appendLine("| Benchmark | Score | Rating |")
            appendLine("|-----------|-------|--------|")
            benchmarkResults.forEach { (name, b) ->
                appendLine("| $name | ${"%.2f".format(b.score)} ${b.unit} | ${b.rating} |")
            }
        }
    }
}

data class BenchmarkResult(val score: Double, val unit: String, val rating: String)
