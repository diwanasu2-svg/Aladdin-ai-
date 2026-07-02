package com.aladdin.security.validation

/**
 * Detects and blocks SQL injection patterns.
 * Provides safe query helpers using parameterised queries.
 */
object SqlInjectionPreventer {

    private val SQL_KEYWORDS = Regex(
        """(?i)\b(union|select|insert|update|delete|drop|truncate|alter|exec|execute|
        |xp_|sp_|declare|cast|convert|char|nchar|varchar|nvarchar|
        |benchmark|sleep|waitfor|delay|load_file|outfile|dumpfile)\b""".trimMargin("|"),
        setOf(RegexOption.IGNORE_CASE)
    )

    private val SQL_OPERATORS = Regex(
        """('[\s\S]*?'|--|;|/\*.*?\*/|xp_\w+|0x[0-9a-fA-F]+)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val ALWAYS_TRUE = Regex(
        """(?i)(or|and)\s+['"\d\w]+\s*[=<>!]+\s*['"\d\w]+|
        |(or|and)\s+\d+\s*=\s*\d+|
        |(or|and)\s+'\w+'\s*=\s*'\w+""".trimMargin("|"),
        setOf(RegexOption.IGNORE_CASE)
    )

    data class ValidationResult(val safe: Boolean, val reason: String = "")

    /** Returns true if the string contains SQL injection patterns */
    fun containsInjection(input: String): Boolean =
        SQL_KEYWORDS.containsMatchIn(input) ||
        SQL_OPERATORS.containsMatchIn(input) ||
        ALWAYS_TRUE.containsMatchIn(input)

    fun validate(input: String): ValidationResult = when {
        SQL_KEYWORDS.containsMatchIn(input)  -> ValidationResult(false, "SQL keyword detected")
        SQL_OPERATORS.containsMatchIn(input) -> ValidationResult(false, "SQL operator detected")
        ALWAYS_TRUE.containsMatchIn(input)   -> ValidationResult(false, "SQL tautology detected")
        else                                 -> ValidationResult(true)
    }

    /** Escape a value for use in a LIKE clause */
    fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Strip all SQL metacharacters (use parameterised queries instead whenever possible) */
    fun stripSql(input: String): String =
        input.replace(Regex("[';\"\\-\\/\\*=<>\\(\\)\\[\\]\\{\\}]"), "")

    /** Safe column/table name — only alphanumeric + underscore */
    fun sanitizeIdentifier(name: String): String {
        val safe = name.replace(Regex("[^a-zA-Z0-9_]"), "")
        require(safe.isNotBlank()) { "Invalid SQL identifier" }
        return safe
    }
}
