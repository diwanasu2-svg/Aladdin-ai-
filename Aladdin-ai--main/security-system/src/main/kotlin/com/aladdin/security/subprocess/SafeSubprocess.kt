package com.aladdin.security.subprocess

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Secure subprocess executor:
 *  - Rejects any command not on [CommandWhitelist]
 *  - Enforces per-command timeout (never hangs forever)
 *  - Caps stdout/stderr to prevent memory exhaustion
 *  - Sanitizes output before returning (strips ANSI codes, control chars)
 *  - Never passes user input directly to the shell (uses ProcessBuilder array form)
 */
class SafeSubprocess {

    companion object { private const val TAG = "SafeSubprocess" }

    suspend fun execute(cmd: List<String>): SubprocessResult = withContext(Dispatchers.IO) {
        // 1. Whitelist check
        if (!CommandWhitelist.isAllowed(cmd)) {
            Log.w(TAG, "Blocked: ${cmd.firstOrNull()} not on whitelist")
            return@withContext SubprocessResult.blocked("Command '${cmd.firstOrNull()}' not permitted")
        }

        val config = CommandWhitelist.getConfig(cmd.first())
            ?: return@withContext SubprocessResult.blocked("No config for '${cmd.first()}'")

        Log.d(TAG, "Executing: ${cmd.joinToString(" ").take(80)}")
        val t0 = System.currentTimeMillis()

        val result = withTimeoutOrNull(config.maxTimeoutMs) {
            runCatching {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()

                val stdout = readLimited(BufferedReader(InputStreamReader(process.inputStream)), config.maxOutputBytes)
                val stderr = readLimited(BufferedReader(InputStreamReader(process.errorStream)), 4096)
                val exited = process.waitFor(config.maxTimeoutMs, TimeUnit.MILLISECONDS)
                if (!exited) { process.destroyForcibly() }

                SubprocessResult(
                    success    = exited && process.exitValue() == 0,
                    exitCode   = if (exited) process.exitValue() else -1,
                    stdout     = sanitizeOutput(stdout),
                    stderr     = sanitizeOutput(stderr),
                    durationMs = System.currentTimeMillis() - t0
                )
            }.getOrElse { ex ->
                Log.e(TAG, "Subprocess error", ex)
                SubprocessResult(false, -1, "", ex.message?.take(200) ?: "error",
                    durationMs = System.currentTimeMillis() - t0)
            }
        }

        result ?: SubprocessResult.timeout(System.currentTimeMillis() - t0)
            .also { Log.w(TAG, "Command timed out after ${config.maxTimeoutMs}ms: ${cmd.first()}") }
    }

    /** Convenience: single string command (split on spaces — no shell expansion) */
    suspend fun execute(command: String): SubprocessResult =
        execute(command.trim().split("\\s+".toRegex()))

    private fun readLimited(reader: BufferedReader, maxBytes: Int): String {
        val sb = StringBuilder()
        var total = 0
        reader.forEachLine { line ->
            if (total < maxBytes) {
                sb.appendLine(line)
                total += line.length
            }
        }
        if (total >= maxBytes) sb.appendLine("[output truncated at $maxBytes bytes]")
        return sb.toString()
    }

    private fun sanitizeOutput(output: String): String =
        output
            .replace(Regex("\u001B\\[[\\d;]*[A-Za-z]"), "")   // strip ANSI codes
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")   // strip control chars
            .trim()
}
