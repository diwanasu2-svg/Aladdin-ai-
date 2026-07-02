package com.aladdin.security.validation

/**
 * Central input sanitizer — strips, escapes, or rejects dangerous content.
 * All public methods return a sanitized copy; original is never mutated.
 */
object InputSanitizer {

    private val NULL_BYTES       = Regex("\u0000")
    private val CONTROL_CHARS    = Regex("[\u0001-\u001F\u007F]")
    private val UNICODE_OVERLONG = Regex("[\uFFFE\uFFFF]")
    private val PATH_TRAVERSAL   = Regex("""(\.\.[\\/]|[\\/]\.\.|\.\.)""")

    /** General-purpose sanitizer: strips null bytes, controls, and trims */
    fun sanitize(input: String, maxLength: Int = 4096): String {
        return input
            .replace(NULL_BYTES, "")
            .replace(CONTROL_CHARS, "")
            .replace(UNICODE_OVERLONG, "")
            .trim()
            .take(maxLength)
    }

    /** Sanitize for use in Android log output (prevents log injection) */
    fun sanitizeForLog(input: String, maxLength: Int = 200): String =
        sanitize(input, maxLength)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /** Sanitize a file/path name — strips traversal sequences */
    fun sanitizePath(input: String): String =
        sanitize(input)
            .replace(PATH_TRAVERSAL, "")
            .replace(Regex("[<>:\"|?*]"), "")   // Windows reserved chars
            .trimStart('/', '\\')

    /** Escape for safe inclusion in HTML */
    fun escapeHtml(input: String): String = buildString(input.length + 16) {
        input.forEach { c ->
            when (c) {
                '<'  -> append("&lt;")
                '>'  -> append("&gt;")
                '&'  -> append("&amp;")
                '"'  -> append("&quot;")
                '\'' -> append("&#x27;")
                '/'  -> append("&#x2F;")
                else -> append(c)
            }
        }
    }

    /** Escape for safe inclusion in a JSON string value */
    fun escapeJson(input: String): String = buildString {
        input.forEach { c ->
            when (c) {
                '"'  -> append("\\\"")
                '\\'  -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
            }
        }
    }

    /** Escape for safe inclusion in a shell argument (single-quote wrapping) */
    fun escapeShell(input: String): String = "'" + input.replace("'", "'\\''") + "'"

    /** Strip all non-alphanumeric characters except space, dash, underscore */
    fun stripSpecialChars(input: String): String =
        input.replace(Regex("[^a-zA-Z0-9 _\\-]"), "")

    /** Normalise to ASCII-safe printable characters */
    fun toAsciiSafe(input: String): String =
        input.filter { it.code in 32..126 }
}
