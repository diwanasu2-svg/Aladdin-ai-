package com.aladdin.app.security

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 12 Item 8: Security Analyzer — threat detection + behavioral analysis ─

@Singleton
class SecurityAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLogger: AuditLogger
) {
    companion object { private const val TAG = "SecurityAnalyzer" }

    data class ThreatAssessment(
        val level: ThreatLevel,
        val threats: List<String>,
        val recommendations: List<String>,
        val score: Int    // 0–100, higher = more at risk
    )

    enum class ThreatLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    // ── Analyze recent audit events for threat patterns ─────────────────────
    suspend fun assessThreats(): ThreatAssessment = withContext(Dispatchers.IO) {
        val threats = mutableListOf<String>()
        val recs    = mutableListOf<String>()
        var score   = 0

        try {
            // Check failed auth attempts
            val failedAuths = auditLogger.search(
                type  = AuditLogger.EventType.AUTH_FAILED,
                since = System.currentTimeMillis() - 3_600_000  // last hour
            )
            if (failedAuths.size > 5) {
                threats.add("${failedAuths.size} failed auth attempts in the last hour")
                recs.add("Consider enabling 2FA or temporary lockout")
                score += 30
            }

            // Check for API errors (possible injection attempts)
            val apiErrors = auditLogger.search(
                type  = AuditLogger.EventType.API_ERROR,
                since = System.currentTimeMillis() - 1_800_000  // 30 min
            )
            if (apiErrors.size > 20) {
                threats.add("High API error rate: ${apiErrors.size} errors in 30 min")
                recs.add("Check for malformed requests or injection attempts")
                score += 20
            }

            // Check for security violations
            val violations = auditLogger.search(type = AuditLogger.EventType.SECURITY_VIOLATION)
            if (violations.isNotEmpty()) {
                threats.add("${violations.size} security violations recorded")
                recs.add("Review security violation logs immediately")
                score += 40
            }

        } catch (e: Exception) {
            Log.e(TAG, "assessThreats error: ${e.message}")
        }

        val level = when {
            score >= 70 -> ThreatLevel.CRITICAL
            score >= 50 -> ThreatLevel.HIGH
            score >= 30 -> ThreatLevel.MEDIUM
            score >= 10 -> ThreatLevel.LOW
            else        -> ThreatLevel.SAFE
        }

        if (recs.isEmpty()) recs.add("No immediate threats detected. Continue monitoring.")
        ThreatAssessment(level, threats, recs, score)
    }

    // ── Validate incoming input for injection patterns ─────────────────────
    fun detectInjection(input: String): InjectionResult {
        val lower = input.lowercase()
        val threats = mutableListOf<String>()

        // SQL injection patterns
        val sqlPatterns = listOf("'; drop", "' or '1'='1", "union select", "insert into", "delete from", "--")
        if (sqlPatterns.any { lower.contains(it) }) threats.add("SQL injection")

        // Command injection
        val cmdPatterns = listOf("; rm ", "&& rm", "| cat /etc", "`id`", "$(id)", "/etc/passwd")
        if (cmdPatterns.any { lower.contains(it) }) threats.add("Command injection")

        // XSS
        val xssPatterns = listOf("<script>", "javascript:", "onerror=", "onload=", "alert(")
        if (xssPatterns.any { lower.contains(it) }) threats.add("XSS")

        // Path traversal
        if (input.contains("../") || input.contains("..\\")) threats.add("Path traversal")

        if (threats.isNotEmpty()) {
            Log.w(TAG, "Injection detected: ${threats.joinToString()} in: ${input.take(50)}")
        }

        return InjectionResult(
            safe         = threats.isEmpty(),
            threats      = threats,
            sanitized    = sanitize(input)
        )
    }

    fun sanitize(input: String): String = input
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
        .replace(";", "").replace("--", "")
        .take(10_000)

    data class InjectionResult(
        val safe: Boolean,
        val threats: List<String>,
        val sanitized: String
    )

    // ── Check app integrity ────────────────────────────────────────────────
    suspend fun checkAppIntegrity(): List<String> = withContext(Dispatchers.IO) {
        val issues = mutableListOf<String>()
        try {
            val pm  = context.packageManager
            val pkg = context.packageName
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
            if (info.signatures.isNullOrEmpty()) {
                issues.add("App signature not found — possible tampering")
            }

            // Check for root indicators
            val rootIndicators = listOf("/system/app/Superuser.apk", "/sbin/su", "/system/xbin/su")
            if (rootIndicators.any { java.io.File(it).exists() }) {
                issues.add("Root indicators detected — device may be rooted")
            }

            // Check debuggable flag
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                issues.add("App is running in debug mode — not suitable for production")
            }

        } catch (e: Exception) {
            Log.e(TAG, "integrity check error: ${e.message}")
        }
        issues
    }
}
