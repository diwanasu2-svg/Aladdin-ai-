package com.aladdin.security.validation

/**
 * Detects and mitigates Cross-Site Scripting (XSS) patterns.
 * Relevant when Aladdin renders user content in a WebView.
 */
object XssPreventer {

    private val SCRIPT_TAG    = Regex("""<\s*script[\s\S]*?>[\s\S]*?<\s*/\s*script\s*>""", RegexOption.IGNORE_CASE)
    private val EVENT_HANDLER = Regex("""\bon\w+\s*=\s*['"]?[\s\S]*?['"]?""", RegexOption.IGNORE_CASE)
    private val JAVASCRIPT    = Regex("""javascript\s*:""", RegexOption.IGNORE_CASE)
    private val DATA_URI      = Regex("""data\s*:\s*text/html""", RegexOption.IGNORE_CASE)
    private val HTML_TAG      = Regex("""<[^>]+>""")
    private val ENTITY_REF    = Regex("""&\w+;|&#\d+;|&#x[0-9a-fA-F]+;""")
    private val VBSCRIPT      = Regex("""vbscript\s*:""", RegexOption.IGNORE_CASE)

    data class ValidationResult(val safe: Boolean, val reason: String = "")

    fun containsXss(input: String): Boolean =
        SCRIPT_TAG.containsMatchIn(input) ||
        EVENT_HANDLER.containsMatchIn(input) ||
        JAVASCRIPT.containsMatchIn(input) ||
        DATA_URI.containsMatchIn(input) ||
        VBSCRIPT.containsMatchIn(input)

    fun validate(input: String): ValidationResult = when {
        SCRIPT_TAG.containsMatchIn(input)    -> ValidationResult(false, "Script tag detected")
        JAVASCRIPT.containsMatchIn(input)    -> ValidationResult(false, "JavaScript URL detected")
        VBSCRIPT.containsMatchIn(input)      -> ValidationResult(false, "VBScript URL detected")
        DATA_URI.containsMatchIn(input)      -> ValidationResult(false, "Data URI detected")
        EVENT_HANDLER.containsMatchIn(input) -> ValidationResult(false, "Event handler detected")
        else                                 -> ValidationResult(true)
    }

    /** HTML-encode the input to safely render in a WebView */
    fun encode(input: String): String = InputSanitizer.escapeHtml(input)

    /** Strip all HTML tags (keep text content) */
    fun stripTags(input: String): String = HTML_TAG.replace(input, "")

    /** Sanitize for safe WebView injection — keeps basic formatting but removes scripts */
    fun sanitizeForWebView(input: String): String =
        input.let { SCRIPT_TAG.replace(it, "") }
             .let { EVENT_HANDLER.replace(it, "") }
             .let { JAVASCRIPT.replace(it, "#") }
             .let { VBSCRIPT.replace(it, "#") }
             .let { DATA_URI.replace(it, "#") }
}
