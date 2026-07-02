package com.aladdin.security.exceptions

import android.util.Log

/**
 * Intercepts exceptions before they reach logs or the UI.
 * Strips sensitive data: API keys, tokens, passwords, file paths, stack frame details.
 * Never leaks internal implementation details to user-visible surfaces.
 */
object SecureExceptionHandler {

    private const val TAG = "SecureExceptionHandler"

    private val SENSITIVE_PATTERNS = listOf(
        Regex("""(?i)(api[_-]?key|token|password|secret|bearer|auth|credential)\s*[=:]\s*\S+"""),
        Regex("""[A-Za-z0-9+/]{40,}={0,2}"""),       // Base64 secrets
        Regex("""sk-[A-Za-z0-9]{32,}"""),             // OpenAI-style keys
        Regex("""AIza[0-9A-Za-z_\-]{35}"""),          // Google API key
        Regex("""/data/data/[^\s]+"""),                // Internal app paths
        Regex("""com\.aladdin\.\S+\.\S+\(.*:\d+\)""") // Stack frames with line numbers
    )

    data class SafeError(
        val userMessage: String,
        val errorCode: String,
        val category: ErrorCategory,
        val loggable: String          // safe version for logs (no PII/secrets)
    )

    enum class ErrorCategory {
        SECRET_ACCESS, INPUT_VALIDATION, SUBPROCESS, CONFIG, STORAGE, NETWORK, UNKNOWN
    }

    /** Convert any throwable to a safe, loggable error */
    fun handle(throwable: Throwable, context: String = ""): SafeError {
        val safeMessage = sanitizeMessage(throwable.message ?: "")
        val category    = categorize(throwable)
        val errorCode   = "${category.name}_${System.currentTimeMillis() % 100000}"

        Log.e(TAG, "[$errorCode] ${context.take(40)}: ${throwable.javaClass.simpleName} — $safeMessage")

        return SafeError(
            userMessage  = userFriendlyMessage(throwable),
            errorCode    = errorCode,
            category     = category,
            loggable     = "$context: ${throwable.javaClass.simpleName} — $safeMessage"
        )
    }

    /** Strip any sensitive data from a message string */
    fun sanitizeMessage(message: String): String {
        var result = message
        SENSITIVE_PATTERNS.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val key = match.value.substringBefore("=").substringBefore(":").take(10)
                "$key=[REDACTED]"
            }
        }
        return result.take(500)
    }

    /** Safe log wrapper — never logs raw exception messages */
    fun logSafely(tag: String, message: String, throwable: Throwable? = null) {
        val safe = sanitizeMessage(message)
        if (throwable != null) {
            Log.e(tag, safe, SanitizedException(throwable))
        } else {
            Log.e(tag, safe)
        }
    }

    private fun categorize(t: Throwable): ErrorCategory = when (t) {
        is SecretNotFoundException, is SecretStorageException, is KeystoreOperationException -> ErrorCategory.SECRET_ACCESS
        is SecurityValidationException                                                        -> ErrorCategory.INPUT_VALIDATION
        is BlockedCommandException                                                            -> ErrorCategory.SUBPROCESS
        is ConfigIntegrityException                                                           -> ErrorCategory.CONFIG
        is SecureStorageException                                                             -> ErrorCategory.STORAGE
        else -> ErrorCategory.UNKNOWN
    }

    private fun userFriendlyMessage(t: Throwable): String = UserFriendlyErrorMapper.map(t)

    /** Wraps an exception replacing its message with a sanitized version */
    private class SanitizedException(original: Throwable) :
        Exception("[${original.javaClass.simpleName}] ${sanitizeMessage(original.message ?: "")}")
}
