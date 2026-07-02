import dagger.hilt.android.qualifiers.ApplicationContext

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Phase 5 – Safety Agent (Highest Priority)
 *
 * Responsibilities:
 *  - Detect dangerous commands (shell injection, data deletion, root access)
 *  - Protect sensitive data (passwords, keys, PII)
 *  - Check permissions before tool execution
 *  - Block unsafe actions and emit an emergency interrupt
 *  - Validate tool execution requests
 *  - Enforce security policies
 */
@Singleton
class SafetyAgent @Inject constructor(
    private val prioritySystem: AgentPrioritySystem
) {
    companion object {
        private const val TAG = "SafetyAgent"

        private val DANGEROUS_PATTERNS = listOf(
            Regex("rm\\s+-rf", RegexOption.IGNORE_CASE),
            Regex("format\\s+[a-zA-Z]:", RegexOption.IGNORE_CASE),
            Regex("drop\\s+table", RegexOption.IGNORE_CASE),
            Regex("delete\\s+from", RegexOption.IGNORE_CASE),
            Regex("exec\\(", RegexOption.IGNORE_CASE),
            Regex("eval\\(", RegexOption.IGNORE_CASE),
            Regex("system\\(", RegexOption.IGNORE_CASE),
            Regex("Runtime\\.getRuntime"),
            Regex("su\\b"),
            Regex("chmod\\s+777"),
            Regex("\\bwget\\b"),
            Regex("\\bcurl\\b.*>.*\\.sh"),
            Regex("shutdown|reboot|halt", RegexOption.IGNORE_CASE)
        )

        private val SENSITIVE_PATTERNS = listOf(
            Regex("password\\s*[:=]", RegexOption.IGNORE_CASE),
            Regex("api[_-]?key\\s*[:=]", RegexOption.IGNORE_CASE),
            Regex("secret\\s*[:=]", RegexOption.IGNORE_CASE),
            Regex("token\\s*[:=]", RegexOption.IGNORE_CASE),
            Regex("\\b[0-9]{16}\\b"),        // credit card-like
            Regex("\\b[0-9]{3}-[0-9]{2}-[0-9]{4}\\b"),  // SSN-like
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")  // email
        )

        private val REQUIRED_PERMISSIONS = mapOf(
            "camera"   to "android.permission.CAMERA",
            "location" to "android.permission.ACCESS_FINE_LOCATION",
            "sms"      to "android.permission.SEND_SMS",
            "contacts" to "android.permission.READ_CONTACTS",
            "storage"  to "android.permission.READ_EXTERNAL_STORAGE",
            "call"     to "android.permission.CALL_PHONE",
            "record"   to "android.permission.RECORD_AUDIO"
        )
    }

    data class SafetyReport(
        val isSafe: Boolean,
        val reason: String = "",
        val violations: List<String> = emptyList(),
        val redactedContent: String = ""
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val securityLog = mutableListOf<String>()
    private val blockedActions = mutableListOf<String>()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.SAFETY ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Safety Agent started (priority=1)")
    }

    // ── Core safety checks ──────────────────────────────────────────────────

    /** Full safety validation — call before any tool execution. */
    fun validate(content: String, toolName: String? = null): SafetyReport {
        val violations = mutableListOf<String>()

        // 1. Dangerous command detection
        DANGEROUS_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(content)) {
                violations.add("Dangerous pattern detected: ${pattern.pattern}")
            }
        }

        // 2. Sensitive data detection
        val sensitiveMatches = SENSITIVE_PATTERNS.filter { it.containsMatchIn(content) }
        if (sensitiveMatches.isNotEmpty()) {
            violations.add("Sensitive data exposure risk (${sensitiveMatches.size} patterns)")
        }

        // 3. Permission check for known tools
        toolName?.let { tool ->
            val requiredPermission = REQUIRED_PERMISSIONS[tool.lowercase()]
            if (requiredPermission != null) {
                violations.add("Tool '$tool' requires permission: $requiredPermission — verify granted before use")
            }
        }

        return if (violations.isEmpty()) {
            SafetyReport(isSafe = true)
        } else {
            val reason = violations.joinToString("; ")
            logBlocked("BLOCKED [$toolName]: $reason")
            SafetyReport(
                isSafe = false,
                reason = reason,
                violations = violations,
                redactedContent = redact(content)
            )
        }
    }

    /** Redact sensitive data from a string before logging or displaying. */
    fun redact(content: String): String {
        var result = content
        SENSITIVE_PATTERNS.forEach { pattern ->
            result = result.replace(pattern, "[REDACTED]")
        }
        return result
    }

    /** Check whether a specific action is allowed under current security policy. */
    fun isActionAllowed(action: String, context: Map<String, Any> = emptyMap()): Boolean {
        val report = validate(action)
        if (!report.isSafe) {
            Log.w(TAG, "Action blocked: ${report.reason}")
            return false
        }
        return true
    }

    /** Block an unsafe action and emit an emergency interrupt if critical. */
    suspend fun blockAction(
        action: String,
        taskId: String,
        isEmergency: Boolean = false
    ): SafetyReport {
        val report = validate(action)
        if (!report.isSafe) {
            logBlocked("Action blocked (task=$taskId): ${report.reason}")
            if (isEmergency) {
                triggerEmergencyBlock(taskId, report.reason)
            }
        }
        return report
    }

    /** Enforce a named security policy. */
    fun enforcePolicy(policyName: String, value: Any): Boolean {
        return when (policyName.lowercase()) {
            "max_output_length"    -> (value as? Int)?.let { it <= 10_000 } ?: false
            "no_root_access"       -> value != "root"
            "no_shell_commands"    -> !validate(value.toString()).violations.any { it.contains("Dangerous") }
            "data_retention_days"  -> (value as? Int)?.let { it <= 365 } ?: false
            else -> {
                Log.w(TAG, "Unknown policy: $policyName")
                true
            }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        if (msg.type == AgentCommunication.MessageType.EMERGENCY) {
            Log.e(TAG, "Emergency received from ${msg.sender}: ${msg.payload}")
        }

        val content = msg.payload["content"]?.toString() ?: return
        val report = validate(content)
        if (!report.isSafe) {
            AgentCommunication.reportResult(
                sender = AgentCommunication.AgentType.SAFETY,
                receiver = msg.sender,
                taskId = msg.taskId,
                result = mapOf("blocked" to true, "reason" to report.reason),
                success = false
            )
        }
    }

    private suspend fun triggerEmergencyBlock(taskId: String, reason: String) {
        prioritySystem.emergency(
            taskId = taskId,
            agentType = AgentCommunication.AgentType.SAFETY,
            description = "Emergency block: $reason",
            action = {
                Log.e(TAG, "EMERGENCY BLOCK triggered: $reason")
                AgentCommunication.broadcastContext(
                    AgentCommunication.AgentType.SAFETY,
                    taskId,
                    "emergency_block",
                    mapOf("reason" to reason, "timestamp" to System.currentTimeMillis())
                )
                "blocked"
            }
        )
    }

    private fun logBlocked(message: String) {
        Log.w(TAG, message)
        securityLog.add("[${System.currentTimeMillis()}] $message")
        blockedActions.add(message)
        if (securityLog.size > 200) securityLog.removeAt(0)
    }

    fun getSecurityLog(): List<String> = securityLog.toList()
    fun getBlockedActions(): List<String> = blockedActions.toList()
}
