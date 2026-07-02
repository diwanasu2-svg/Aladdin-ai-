package com.aladdin.reliability.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DiagnosticsRunner(private val context: Context) {

    companion object {
        private const val TAG = "DiagnosticsRunner"
        private const val REPORTS_DIR = "diagnostic_reports"
        private const val MAX_REPORTS = 10
    }

    private val validator  = ComponentValidator(context)
    private val benchmark  = PerformanceBenchmark()

    suspend fun runFull(): DiagnosticReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "Running full diagnostics...")
        val t0 = System.currentTimeMillis()

        val validationDeferred  = async { validator.validateAll() }
        val benchmarkDeferred   = async { benchmark.runAll() }

        val results     = validationDeferred.await()
        val benchmarks  = benchmarkDeferred.await()

        val report = DiagnosticReport(results = results, benchmarkResults = benchmarks)
        Log.i(TAG, "Diagnostics complete in ${System.currentTimeMillis() - t0}ms — " +
            "${report.passed} pass, ${report.warned} warn, ${report.failed} fail")
        saveReport(report)
        report
    }

    /** Run only component validation (fast, no benchmarks) */
    suspend fun runValidationOnly(): DiagnosticReport = withContext(Dispatchers.IO) {
        DiagnosticReport(results = validator.validateAll())
    }

    private fun saveReport(report: DiagnosticReport) {
        try {
            val dir = File(context.filesDir, REPORTS_DIR).also { it.mkdirs() }
            val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.timestampMs))
            val file = File(dir, "diag_$ts.md")
            file.writeText(report.toMarkdown())
            Log.i(TAG, "Diagnostic report saved: ${file.name}")
            pruneReports(dir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save diagnostic report", e)
        }
    }

    fun getReports(): List<File> =
        File(context.filesDir, REPORTS_DIR)
            .listFiles { f -> f.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun getLatestReport(): String? = getReports().firstOrNull()?.readText()

    private fun pruneReports(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }
}
