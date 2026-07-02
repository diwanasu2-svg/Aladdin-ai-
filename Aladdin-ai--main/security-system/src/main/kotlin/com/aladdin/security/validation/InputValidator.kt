package com.aladdin.security.validation

/**
 * All-in-one input validator. Combines SQL, command injection, XSS checks
 * and format validation for every type of user input Aladdin accepts.
 */
class InputValidator {

    enum class InputType { TEXT, COMMAND, SQL, URL, EMAIL, PATH, FILENAME, JSON }

    data class ValidationResult(
        val valid: Boolean,
        val sanitized: String = "",
        val violations: List<String> = emptyList()
    ) {
        val hasViolations get() = violations.isNotEmpty()
        fun requireValid() { if (!valid) throw SecurityValidationException(violations.joinToString("; ")) }
    }

    class SecurityValidationException(message: String) : Exception("Input validation failed: $message")

    /**
     * Validate and sanitize input of a given type.
     * Always returns a [ValidationResult]; never throws for invalid input.
     */
    fun validate(input: String, type: InputType, maxLength: Int = 4096): ValidationResult {
        val sanitized = InputSanitizer.sanitize(input, maxLength)
        val violations = mutableListOf<String>()

        // Universal checks
        if (input.length > maxLength) violations.add("Input exceeds max length ($maxLength)")

        // Type-specific checks
        when (type) {
            InputType.TEXT     -> checkText(sanitized, violations)
            InputType.COMMAND  -> checkCommand(sanitized, violations)
            InputType.SQL      -> checkSql(sanitized, violations)
            InputType.URL      -> checkUrl(sanitized, violations)
            InputType.EMAIL    -> checkEmail(sanitized, violations)
            InputType.PATH     -> checkPath(sanitized, violations)
            InputType.FILENAME -> checkFilename(sanitized, violations)
            InputType.JSON     -> checkJson(sanitized, violations)
        }

        val finalSanitized = when (type) {
            InputType.TEXT     -> sanitized
            InputType.COMMAND  -> CommandInjectionPreventer.strip(sanitized)
            InputType.SQL      -> SqlInjectionPreventer.stripSql(sanitized)
            InputType.URL      -> sanitized
            InputType.EMAIL    -> sanitized.lowercase().trim()
            InputType.PATH     -> InputSanitizer.sanitizePath(sanitized)
            InputType.FILENAME -> InputSanitizer.sanitizePath(sanitized)
            InputType.JSON     -> sanitized
        }

        return ValidationResult(
            valid     = violations.isEmpty(),
            sanitized = finalSanitized,
            violations = violations
        )
    }

    /** Shorthand for validating user-visible text input */
    fun validateText(input: String, maxLength: Int = 4096) = validate(input, InputType.TEXT, maxLength)

    /** Shorthand for validating file paths */
    fun validatePath(input: String) = validate(input, InputType.PATH)

    /** Shorthand for validating SQL fragments (prefer parameterised queries) */
    fun validateSql(input: String) = validate(input, InputType.SQL)

    // ─── Type Checks ──────────────────────────────────────────────────────────

    private fun checkText(input: String, violations: MutableList<String>) {
        if (XssPreventer.containsXss(input)) violations.add("XSS pattern detected")
        if (SqlInjectionPreventer.containsInjection(input)) violations.add("SQL injection pattern detected")
    }

    private fun checkCommand(input: String, violations: MutableList<String>) {
        val r = CommandInjectionPreventer.validate(input)
        if (!r.safe) violations.add(r.reason)
    }

    private fun checkSql(input: String, violations: MutableList<String>) {
        val r = SqlInjectionPreventer.validate(input)
        if (!r.safe) violations.add(r.reason)
    }

    private fun checkUrl(input: String, violations: MutableList<String>) {
        if (!input.matches(Regex("""https?://[\w\-./?#\[\]@!$&'()*+,;=%]+""")))
            violations.add("Invalid URL format")
        if (XssPreventer.containsXss(input)) violations.add("XSS in URL")
    }

    private fun checkEmail(input: String, violations: MutableList<String>) {
        if (!input.matches(Regex("""^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$""")))
            violations.add("Invalid email format")
    }

    private fun checkPath(input: String, violations: MutableList<String>) {
        if (input.contains("..")) violations.add("Path traversal attempt")
        if (input.contains('\u0000')) violations.add("Null byte in path")
    }

    private fun checkFilename(input: String, violations: MutableList<String>) {
        checkPath(input, violations)
        if (input.contains('/') || input.contains('\\')) violations.add("Directory separator in filename")
    }

    private fun checkJson(input: String, violations: MutableList<String>) {
        try {
            org.json.JSONObject(input)
        } catch (e1: Exception) {
            try { org.json.JSONArray(input) } catch (e2: Exception) {
                violations.add("Invalid JSON")
            }
        }
    }
}
