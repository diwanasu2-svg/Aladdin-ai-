package com.aladdin.reliability.validation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

sealed class ValidationResult {
    data class Pass(val message: String) : ValidationResult()
    data class Fail(val message: String) : ValidationResult()
    data class Warn(val message: String) : ValidationResult()
}

data class StartupValidationReport(
    val results: Map<String, ValidationResult>,
    val allPassed: Boolean,
    val hasFatal: Boolean
) {
    fun summary(): String = buildString {
        appendLine("=== Startup Validation ===")
        results.forEach { (name, result) ->
            val icon = when (result) {
                is ValidationResult.Pass -> "✓"; is ValidationResult.Fail -> "✗"; is ValidationResult.Warn -> "⚠"
            }
            val msg = when (result) {
                is ValidationResult.Pass -> result.message
                is ValidationResult.Fail -> result.message
                is ValidationResult.Warn -> result.message
            }
            appendLine("  $icon $name: $msg")
        }
        appendLine("Result: ${if (hasFatal) "FATAL ERRORS PRESENT" else if (allPassed) "ALL PASSED" else "WARNINGS"}")
    }
}

/**
 * Runs all startup validation checks in parallel and returns a consolidated report.
 */
class StartupValidator(
    private val context: Context,
    private val requiredPermissions: List<String> = emptyList(),
    private val optionalPermissions: List<String> = emptyList()
) {
    companion object { private const val TAG = "StartupValidator" }

    private val permChecker = PermissionChecker(context)
    private val depChecker  = DependencyVersionChecker(context)
    private val cfgValidator = ConfigValidator(context)

    suspend fun validate(): StartupValidationReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "Running startup validation...")

        val permResult  = async { "Permissions"         to permChecker.check(requiredPermissions, optionalPermissions) }
        val depResult   = async { "Dependency Versions" to depChecker.check() }
        val storageResult = async { "Storage"           to validateStorage() }
        val memResult   = async { "Minimum Memory"      to validateMemory() }

        val results = listOf(permResult, depResult, storageResult, memResult)
            .map { it.await() }.toMap()

        val hasFatal = results.values.any { it is ValidationResult.Fail }
        val allPassed = results.values.all { it is ValidationResult.Pass }

        StartupValidationReport(results, allPassed, hasFatal).also {
            Log.i(TAG, it.summary())
        }
    }

    private fun validateStorage(): ValidationResult {
        val filesDir = context.filesDir
        return if (filesDir.canWrite()) ValidationResult.Pass("Internal storage writable")
        else ValidationResult.Fail("Internal storage NOT writable: ${filesDir.absolutePath}")
    }

    private fun validateMemory(): ValidationResult {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        val availMb = mi.availMem / (1024 * 1024)
        return when {
            availMb >= 256 -> ValidationResult.Pass("${availMb}MB available RAM")
            availMb >= 128 -> ValidationResult.Warn("Low RAM: only ${availMb}MB available")
            else           -> ValidationResult.Fail("Critical RAM: only ${availMb}MB available (min 128MB)")
        }
    }
}
