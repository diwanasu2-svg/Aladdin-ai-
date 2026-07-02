package com.aladdin.security.subprocess

/**
 * Strict allow-list of commands that Aladdin is permitted to execute.
 * Any command not on this list is rejected before execution.
 */
object CommandWhitelist {

    data class AllowedCommand(
        val executable: String,
        val allowedArgs: Set<String> = emptySet(),   // empty = any args allowed
        val requiresArgAllowList: Boolean = false,
        val maxOutputBytes: Int = 65536,
        val maxTimeoutMs: Long = 5_000L
    )

    private val ALLOWED: Map<String, AllowedCommand> = mapOf(
        "logcat"  to AllowedCommand("logcat",  maxOutputBytes = 1024 * 1024, maxTimeoutMs = 10_000),
        "getprop" to AllowedCommand("getprop", requiresArgAllowList = false, maxTimeoutMs = 3_000),
        "dumpsys" to AllowedCommand("dumpsys",
            allowedArgs          = setOf("battery", "cpuinfo", "meminfo", "wifi", "connectivity"),
            requiresArgAllowList = true, maxTimeoutMs = 5_000),
        "am"      to AllowedCommand("am",
            allowedArgs          = setOf("broadcast", "start-foreground-service"),
            requiresArgAllowList = true),
        "pm"      to AllowedCommand("pm",
            allowedArgs          = setOf("list", "path"),
            requiresArgAllowList = true),
        "date"    to AllowedCommand("date",   maxTimeoutMs = 1_000),
        "uname"   to AllowedCommand("uname",  maxTimeoutMs = 1_000)
    )

    fun isAllowed(cmd: List<String>): Boolean {
        if (cmd.isEmpty()) return false
        val entry = ALLOWED[cmd.first()] ?: return false
        if (!entry.requiresArgAllowList || entry.allowedArgs.isEmpty()) return true
        // First real argument must be in the allow list
        val firstArg = cmd.getOrNull(1) ?: return true
        return firstArg in entry.allowedArgs
    }

    fun getConfig(executable: String): AllowedCommand? = ALLOWED[executable]

    fun allowedExecutables(): Set<String> = ALLOWED.keys
}
