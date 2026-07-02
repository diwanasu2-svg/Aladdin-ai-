package com.aladdin.reliability.validation

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class PermissionCheckResult(
    val permission: String,
    val granted: Boolean,
    val required: Boolean
)

class PermissionChecker(private val context: Context) {

    fun check(required: List<String>, optional: List<String> = emptyList()): ValidationResult {
        val results = mutableListOf<PermissionCheckResult>()
        val missing = mutableListOf<String>()

        (required.map { it to true } + optional.map { it to false }).forEach { (perm, req) ->
            val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            results.add(PermissionCheckResult(perm, granted, req))
            if (req && !granted) missing.add(perm.substringAfterLast("."))
        }

        return if (missing.isEmpty()) {
            ValidationResult.Pass("All ${required.size} required permissions granted")
        } else {
            ValidationResult.Fail("Missing required permissions: ${missing.joinToString()}")
        }
    }
}
