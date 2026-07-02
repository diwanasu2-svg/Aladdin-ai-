package com.aladdin.security.validation

/**
 * Detects and blocks OS command injection patterns.
 */
object CommandInjectionPreventer {

    private val SHELL_METACHAR = Regex("""[;&|`$(){}<>\[\]!\\]""")
    private val SUBSHELL       = Regex("""\$\(|`[^`]*`|\$\{""")
    private val REDIRECT       = Regex("""[><]""")
    private val ENV_EXPAND     = Regex("""\$[A-Za-z_][A-Za-z0-9_]*""")

    data class ValidationResult(val safe: Boolean, val reason: String = "")

    fun containsInjection(input: String): Boolean =
        SHELL_METACHAR.containsMatchIn(input) ||
        SUBSHELL.containsMatchIn(input) ||
        REDIRECT.containsMatchIn(input) ||
        ENV_EXPAND.containsMatchIn(input)

    fun validate(input: String): ValidationResult = when {
        SUBSHELL.containsMatchIn(input)       -> ValidationResult(false, "Subshell substitution detected")
        SHELL_METACHAR.containsMatchIn(input) -> ValidationResult(false, "Shell metacharacter detected")
        REDIRECT.containsMatchIn(input)       -> ValidationResult(false, "I/O redirection detected")
        ENV_EXPAND.containsMatchIn(input)     -> ValidationResult(false, "Environment variable expansion detected")
        else                                  -> ValidationResult(true)
    }

    /** Strip all shell metacharacters — use for display only, not for execution */
    fun strip(input: String): String =
        input.replace(SHELL_METACHAR, "")
             .replace(SUBSHELL, "")
             .replace(ENV_EXPAND, "")
             .trim()

    /** Return only safe alphanumeric + dash/underscore/dot chars */
    fun allowList(input: String): String =
        input.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
}
