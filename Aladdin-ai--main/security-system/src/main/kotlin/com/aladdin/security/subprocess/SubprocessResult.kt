package com.aladdin.security.subprocess

data class SubprocessResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val blocked: Boolean = false,
    val blockReason: String = "",
    val durationMs: Long = 0
) {
    companion object {
        fun blocked(reason: String) = SubprocessResult(false, -1, "", "", blocked = true, blockReason = reason)
        fun timeout(durationMs: Long) = SubprocessResult(false, -1, "", "Command timed out", timedOut = true, durationMs = durationMs)
    }
}
