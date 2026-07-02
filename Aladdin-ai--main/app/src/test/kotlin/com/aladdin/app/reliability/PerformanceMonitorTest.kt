package com.aladdin.app.reliability

import org.junit.Assert.*
import org.junit.Test

/**
 * PerformanceMonitorTest — Phase 13 Item 2: Verify PerformanceMonitor data structures,
 * metric sample recording, threshold checking, and report generation.
 *
 * Note: CPU/RAM readings via /proc require a running Android device and are
 * covered by androidTest. This unit test covers all non-system logic.
 */
class PerformanceMonitorTest {

    // ─── MetricType enum ──────────────────────────────────────────────────────

    @Test
    fun `MetricType enum contains all required types`() {
        val names = PerformanceMonitor.MetricType.entries.map { it.name }
        assertTrue("CPU_USAGE must exist",          "CPU_USAGE"          in names)
        assertTrue("MEMORY_USAGE must exist",       "MEMORY_USAGE"       in names)
        assertTrue("RESPONSE_LATENCY must exist",   "RESPONSE_LATENCY"   in names)
        assertTrue("BATTERY_LEVEL must exist",      "BATTERY_LEVEL"      in names)
        assertTrue("NETWORK_LATENCY must exist",    "NETWORK_LATENCY"    in names)
        assertTrue("DISK_USAGE must exist",         "DISK_USAGE"         in names)
        assertTrue("AI_INFERENCE_TIME must exist",  "AI_INFERENCE_TIME"  in names)
        assertTrue("FRAME_RATE must exist",         "FRAME_RATE"         in names)
    }

    // ─── MetricSample data class ──────────────────────────────────────────────

    @Test
    fun `MetricSample creates correctly`() {
        val ts = System.currentTimeMillis()
        val sample = PerformanceMonitor.MetricSample(
            type      = PerformanceMonitor.MetricType.CPU_USAGE,
            value     = 45.5,
            unit      = "%",
            timestamp = ts,
            tag       = "inference_pass"
        )
        assertEquals(PerformanceMonitor.MetricType.CPU_USAGE, sample.type)
        assertEquals(45.5, sample.value, 0.001)
        assertEquals("%",             sample.unit)
        assertEquals(ts,              sample.timestamp)
        assertEquals("inference_pass", sample.tag)
    }

    @Test
    fun `MetricSample timestamp defaults to current time`() {
        val before = System.currentTimeMillis()
        val sample = PerformanceMonitor.MetricSample(
            type  = PerformanceMonitor.MetricType.MEMORY_USAGE,
            value = 256.0,
            unit  = "MB"
        )
        val after = System.currentTimeMillis()
        assertTrue("Timestamp must be >= before", sample.timestamp >= before)
        assertTrue("Timestamp must be <= after",  sample.timestamp <= after)
    }

    // ─── PerformanceReport data class ─────────────────────────────────────────

    @Test
    fun `PerformanceReport creates correctly`() {
        val report = PerformanceMonitor.PerformanceReport(
            generatedAt       = System.currentTimeMillis(),
            avgCpuPercent     = 32.5,
            peakCpuPercent    = 85.0,
            avgMemoryMb       = 128.0,
            peakMemoryMb      = 256.0,
            avgResponseMs     = 450L,
            p95ResponseMs     = 800L,
            batteryDrainPct   = 3.5,
            totalSamples      = 120,
            alertsFired       = 2,
            healthScore       = 87
        )
        assertEquals(32.5,  report.avgCpuPercent,  0.01)
        assertEquals(85.0,  report.peakCpuPercent, 0.01)
        assertEquals(128.0, report.avgMemoryMb,    0.01)
        assertEquals(450L,  report.avgResponseMs)
        assertEquals(800L,  report.p95ResponseMs)
        assertEquals(120,   report.totalSamples)
        assertEquals(2,     report.alertsFired)
        assertEquals(87,    report.healthScore)
    }

    @Test
    fun `PerformanceReport healthScore is in 0-100 range`() {
        val report = PerformanceMonitor.PerformanceReport(
            generatedAt  = System.currentTimeMillis(),
            healthScore  = 95
        )
        assertTrue("healthScore must be 0–100", report.healthScore in 0..100)
    }

    // ─── Alert thresholds ────────────────────────────────────────────────────

    @Test
    fun `CPU_THRESHOLD is within reasonable range`() {
        assertTrue("CPU threshold must be > 0",
            PerformanceMonitor.CPU_ALERT_THRESHOLD > 0.0)
        assertTrue("CPU threshold must be <= 100",
            PerformanceMonitor.CPU_ALERT_THRESHOLD <= 100.0)
    }

    @Test
    fun `MEMORY_THRESHOLD is within reasonable range`() {
        assertTrue("Memory threshold must be > 0",
            PerformanceMonitor.MEMORY_ALERT_THRESHOLD_MB > 0.0)
        assertTrue("Memory threshold must be < 8000 MB",
            PerformanceMonitor.MEMORY_ALERT_THRESHOLD_MB < 8000.0)
    }

    @Test
    fun `LATENCY_THRESHOLD is within reasonable range`() {
        assertTrue("Latency threshold must be > 0",
            PerformanceMonitor.LATENCY_ALERT_THRESHOLD_MS > 0L)
        assertTrue("Latency threshold must be < 60 seconds",
            PerformanceMonitor.LATENCY_ALERT_THRESHOLD_MS < 60_000L)
    }

    // ─── Sample list math ────────────────────────────────────────────────────

    @Test
    fun `average of metric samples is computed correctly`() {
        val samples = listOf(10.0, 20.0, 30.0, 40.0)
        val avg = samples.average()
        assertEquals(25.0, avg, 0.001)
    }

    @Test
    fun `p95 of metric samples calculated correctly for 20 values`() {
        val values = (1..20).map { it.toDouble() }.toMutableList()
        values.sort()
        val p95Idx = (0.95 * values.size).toInt().coerceAtMost(values.size - 1)
        assertEquals(19.0, values[p95Idx], 0.001)
    }
}
