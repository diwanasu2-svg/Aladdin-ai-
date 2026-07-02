package com.aladdin.reliability.crash

data class CrashReport(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val threadName: String,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val appVersion: String,
    val androidVersion: String = android.os.Build.VERSION.RELEASE,
    val device: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
    val availableMemoryMb: Long = 0L,
    val recoveryAttempts: Int = 0
) {
    fun toLogString(): String = buildString {
        appendLine("=== CRASH REPORT [$id] ===")
        appendLine("Time     : ${java.util.Date(timestampMs)}")
        appendLine("Thread   : $threadName")
        appendLine("Exception: $exceptionClass")
        appendLine("Message  : ${message ?: "(none)"}")
        appendLine("Device   : $device  Android $androidVersion  App $appVersion")
        appendLine("Memory   : ${availableMemoryMb}MB available")
        appendLine("Recovery : attempt #$recoveryAttempts")
        appendLine("--- Stack Trace ---")
        appendLine(stackTrace)
        appendLine("===================")
    }
}
