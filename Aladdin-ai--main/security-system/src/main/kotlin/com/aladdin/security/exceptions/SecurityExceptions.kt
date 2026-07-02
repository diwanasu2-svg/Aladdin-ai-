package com.aladdin.security.exceptions

/**
 * Hierarchy of security-specific exceptions.
 * IMPORTANT: All messages are safe for logging — no secrets, keys, or user data.
 */

/** Base class for all Aladdin security exceptions */
sealed class AladdinSecurityException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** A required secret was not found in the vault */
class SecretNotFoundException(key: String) :
    AladdinSecurityException("Secret not found: ${key.take(4)}****")

/** Failed to store or retrieve a secret */
class SecretStorageException(message: String, cause: Throwable? = null) :
    AladdinSecurityException(message, cause)

/** Input validation failed */
class SecurityValidationException(message: String, val violations: List<String> = emptyList()) :
    AladdinSecurityException("Validation failed: $message")

/** A subprocess command was blocked by the whitelist */
class BlockedCommandException(command: String) :
    AladdinSecurityException("Command blocked: '${command.take(20).replace(Regex("[^a-zA-Z0-9_\\-]"), "?")}'")

/** Config integrity check failed (possible tampering) */
class ConfigIntegrityException(message: String, cause: Throwable? = null) :
    AladdinSecurityException(message, cause)

/** Dependency checksum mismatch */
class DependencyIntegrityException(name: String, cause: Throwable? = null) :
    AladdinSecurityException("Dependency integrity check failed: $name", cause)

/** Keystore operation failed */
class KeystoreOperationException(message: String, cause: Throwable? = null) :
    AladdinSecurityException(message, cause)

/** Encrypted storage I/O failure */
class SecureStorageException(message: String, cause: Throwable? = null) :
    AladdinSecurityException(message, cause)

/** Authentication / authorisation failure */
class SecurityAuthException(message: String) :
    AladdinSecurityException(message)
