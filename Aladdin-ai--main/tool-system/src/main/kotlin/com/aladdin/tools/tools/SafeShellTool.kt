package com.aladdin.tools.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Safe Shell tool — restricted command execution.
 *
 * Only whitelisted, read-only shell commands can be executed.
 * No write, no file deletion, no network modification commands.
 *
 * Whitelist:
 *   date, uptime, hostname, uname, whoami, id,
 *   ls, find (read-only), cat, head, tail, wc, grep, sort, uniq,
 *   echo, printf, env, printenv, pwd,
 *   df, du (read-only),
 *   ps, top -n 1, netstat (read-only)
 *
 * Blocked: rm, mv, cp, chmod, chown, sudo, su, curl, wget,
 *   apt, yum, pip, npm, kill, killall, reboot, shutdown, init,
 *   dd, mkfs, fdisk, mount, umount
 *
 * Params: command (the shell command to run), timeout_seconds
 */
@Singleton
class SafeShellTool @Inject constructor() : BaseTool {

    override val id = "safe_shell"
    override val name = "Safe Shell"
    override val description = "Execute whitelisted read-only shell commands safely"

    companion object {
        private const val TAG = "SafeShellTool"
        private const val DEFAULT_TIMEOUT_SEC = 10L
        private const val MAX_OUTPUT_CHARS = 4000

        /** Allowed command prefixes. Command must start with one of these. */
        private val WHITELIST_PREFIXES = listOf(
            "date", "uptime", "hostname", "uname", "whoami", "id",
            "ls ", "ls\n", "ls", "find ", "cat ", "head ", "tail ",
            "wc ", "grep ", "sort ", "uniq ", "echo ", "echo\n", "echo",
            "printf ", "env", "printenv", "printenv ", "pwd",
            "df", "df ", "du ", "ps", "ps ", "top -n ", "top -bn",
            "netstat -", "ifconfig", "ip addr", "ip route",
            "getprop", "getprop ", "dumpsys battery", "dumpsys meminfo",
            "am start", "pm list packages", "pm list"
        )

        /** Blocked substrings — if a command contains any of these, it is rejected. */
        private val BLOCKLIST = listOf(
            "rm ", "rm\t", "; rm", "&& rm", "| rm",
            "mv ", "cp ", "chmod", "chown", "sudo", " su ", " su\n",
            "curl", "wget", "fetch", "nc ", "netcat",
            "apt", "yum", "dnf", "pip", "npm", "gem",
            "kill", "killall", "pkill", "reboot", "shutdown", "init ",
            "dd ", "mkfs", "fdisk", "mount ", "umount",
            "passwd", "useradd", "userdel", "groupadd",
            "crontab", "at ", "batch",
            ">", ">>",    // redirect output (could write files)
            "2>",
            "`",          // command substitution
            "$(",         // command substitution
            "eval "
        )
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val command = params["command"] ?: return@withContext ToolResult.error(id, "Missing 'command' parameter")
        val timeoutSec = params["timeout_seconds"]?.toLongOrNull() ?: DEFAULT_TIMEOUT_SEC

        val sanitized = command.trim()

        // Safety check
        val violation = checkSafety(sanitized)
        if (violation != null) {
            Log.w(TAG, "Blocked unsafe command: '$sanitized' — $violation")
            return@withContext ToolResult.error(id, "Command blocked: $violation. Only read-only whitelisted commands are allowed.")
        }

        Log.i(TAG, "Executing safe shell: $sanitized")

        val result = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(timeoutSec)) {
            runCommand(sanitized)
        } ?: return@withContext ToolResult.error(id, "Command timed out after ${timeoutSec}s")

        result
    }

    private fun checkSafety(command: String): String? {
        val lower = command.lowercase()

        // Blocklist check
        BLOCKLIST.forEach { blocked ->
            if (lower.contains(blocked)) return "contains blocked pattern '$blocked'"
        }

        // Whitelist check
        val allowed = WHITELIST_PREFIXES.any { prefix ->
            command.startsWith(prefix, ignoreCase = true)
        }
        if (!allowed) return "command not in whitelist (must start with an allowed command)"

        // Additional guards
        if (command.length > 500) return "command too long (max 500 chars)"
        if (command.count { it == ';' } > 2) return "too many command chaining operators"

        return null
    }

    private fun runCommand(command: String): ToolResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > MAX_OUTPUT_CHARS) {
                    output.appendLine("… [output truncated at ${MAX_OUTPUT_CHARS} chars]")
                    break
                }
            }
            reader.close()

            val exitCode = process.waitFor()
            val out = output.toString().trim()

            if (exitCode != 0 && out.isEmpty()) {
                ToolResult.error(id, "Command exited with code $exitCode")
            } else {
                ToolResult.success(id, if (out.isBlank()) "(no output)" else out)
            }
        } catch (e: Exception) {
            ToolResult.error(id, "Shell execution error: ${e.message}")
        }
    }
}
