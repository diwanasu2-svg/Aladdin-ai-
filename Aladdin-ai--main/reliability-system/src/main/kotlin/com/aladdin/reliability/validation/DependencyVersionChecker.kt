package com.aladdin.reliability.validation

import android.content.Context
import android.os.Build

class DependencyVersionChecker(private val context: Context) {

    data class DepCheck(val name: String, val minVersion: Int, val actual: Int)

    fun check(): ValidationResult {
        val checks = listOf(
            DepCheck("Android SDK", 26, Build.VERSION.SDK_INT),
            DepCheck("Target SDK",  26, context.applicationInfo.targetSdkVersion)
        )
        val failures = checks.filter { it.actual < it.minVersion }
        return if (failures.isEmpty()) {
            ValidationResult.Pass("All dependency version checks passed (SDK ${Build.VERSION.SDK_INT})")
        } else {
            val msg = failures.joinToString { "${it.name}: requires ${it.minVersion}, got ${it.actual}" }
            ValidationResult.Fail("Dependency version failures: $msg")
        }
    }

    fun getSystemInfo(): Map<String, String> = mapOf(
        "Android Version"  to Build.VERSION.RELEASE,
        "SDK Level"        to Build.VERSION.SDK_INT.toString(),
        "Device"           to "${Build.MANUFACTURER} ${Build.MODEL}",
        "ABI"              to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        "App Target SDK"   to context.applicationInfo.targetSdkVersion.toString()
    )
}
