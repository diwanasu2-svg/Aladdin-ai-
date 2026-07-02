package com.aladdin.security.exceptions

/**
 * Maps security exceptions to user-friendly messages.
 * Messages must NEVER reveal: key names, file paths, internal class names, or stack traces.
 */
object UserFriendlyErrorMapper {

    fun map(throwable: Throwable): String = when (throwable) {
        is SecretNotFoundException    -> "A required configuration item could not be found. Please check your app settings."
        is SecretStorageException     -> "There was a problem accessing secure storage. Please restart the app."
        is KeystoreOperationException -> "A security operation failed. If this persists, re-installing the app may help."
        is SecurityValidationException -> buildString {
            append("Some input could not be accepted")
            if (throwable.violations.isNotEmpty()) {
                append(": ")
                append(throwable.violations.take(2).joinToString(", ") { it.take(60) })
            }
            append(".")
        }
        is BlockedCommandException    -> "That operation is not permitted."
        is ConfigIntegrityException   -> "App configuration could not be verified. Please re-install the app."
        is DependencyIntegrityException -> "A required component failed verification. Please update the app."
        is SecureStorageException     -> "Secure file storage is unavailable. Check available storage space."
        is SecurityAuthException      -> "Authentication failed. Please try again."

        // Fallback for standard Java/Android exceptions
        is SecurityException          -> "A security check failed. Please ensure the app has the required permissions."
        is IllegalArgumentException   -> "Invalid input provided. Please check your input and try again."
        is IllegalStateException      -> "The app is in an unexpected state. Please restart the app."
        is java.io.IOException        -> "A file or network operation failed. Please check your connection and storage."
        is java.net.UnknownHostException -> "Could not reach the server. Please check your internet connection."
        is java.net.SocketTimeoutException -> "The request timed out. Please try again."
        is OutOfMemoryError           -> "The app ran out of memory. Please close other apps and try again."

        else -> "Something went wrong. Please try again. If the problem continues, restart the app."
    }

    /** Short one-liner suitable for a Toast or Snackbar */
    fun mapShort(throwable: Throwable): String = map(throwable).substringBefore(".").take(80) + "."

    /** Severity level for UI presentation */
    fun severity(throwable: Throwable): Severity = when (throwable) {
        is ConfigIntegrityException, is DependencyIntegrityException -> Severity.CRITICAL
        is SecretNotFoundException, is KeystoreOperationException     -> Severity.HIGH
        is SecurityValidationException, is BlockedCommandException    -> Severity.MEDIUM
        else -> Severity.LOW
    }

    enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
}
