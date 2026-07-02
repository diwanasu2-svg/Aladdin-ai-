package com.aladdin.security.manager

import android.content.Context
import android.util.Log
import com.aladdin.security.config.SecureConfigManager
import com.aladdin.security.dependency.DependencyVerifier
import com.aladdin.security.exceptions.SecureExceptionHandler
import com.aladdin.security.secrets.SecretManager
import com.aladdin.security.secrets.SecretRotation
import com.aladdin.security.storage.SecureFileStorage
import com.aladdin.security.storage.SecurePreferences
import com.aladdin.security.subprocess.SafeSubprocess
import com.aladdin.security.validation.InputValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Top-level entry point for the Aladdin Security System.
 *
 * Quick start:
 * ```kotlin
 * val security = SecurityManager(context)
 * security.start()   // Application.onCreate()
 *
 * // Store a secret
 * security.secrets.store("gemini_api_key", apiKey)
 *
 * // Validate input
 * val result = security.validator.validate(userText, InputValidator.InputType.TEXT)
 * result.requireValid()
 *
 * // Run a safe subprocess
 * val out = security.subprocess.execute(listOf("dumpsys", "battery"))
 * ```
 */
class SecurityManager(val context: Context) {

    companion object { private const val TAG = "SecurityManager" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─── Public API ───────────────────────────────────────────────────────────
    val secrets      = SecretManager(context)
    val rotation     = SecretRotation(secrets)
    val validator    = InputValidator()
    val subprocess   = SafeSubprocess()
    val config       = SecureConfigManager(context)
    val fileStorage  = SecureFileStorage(context)
    val preferences  = SecurePreferences(context)
    val depVerifier  = DependencyVerifier(context)
    val exHandler    = SecureExceptionHandler

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        Log.i(TAG, "SecurityManager starting...")
        verifyAppIntegrityAsync()
        scheduleSecretRotationCheck()
        Log.i(TAG, "SecurityManager started")
    }

    fun stop() {
        Log.i(TAG, "SecurityManager stopped")
    }

    // ─── Convenience helpers ──────────────────────────────────────────────────

    /** Store an API key securely (high-security, double-encrypted) */
    fun storeApiKey(name: String, key: String) = secrets.storeHighSecurity(name, key)

    /** Retrieve an API key (decrypted on demand) */
    fun getApiKey(name: String): String = secrets.retrieveHighSecurity(name)

    /** Validate user text — throws if invalid */
    fun requireValidText(text: String, maxLength: Int = 4096) =
        validator.validate(text, InputValidator.InputType.TEXT, maxLength).requireValid()

    /** Validate a file path — throws if invalid */
    fun requireValidPath(path: String) =
        validator.validate(path, InputValidator.InputType.PATH).requireValid()

    /** Format a throwable into a user-safe error message */
    fun userMessage(t: Throwable): String = exHandler.handle(t).userMessage

    /** Log safely — strips secrets and sensitive data */
    fun logSafe(tag: String, message: String, t: Throwable? = null) =
        exHandler.logSafely(tag, message, t)

    fun status(): String = buildString {
        appendLine("=== Security System Status ===")
        appendLine("Secrets store: ${if (secrets.exists("___ok")) "OK" else "ready"}")
        appendLine("Secure prefs:  ready")
        appendLine("File storage:  ${fileStorage.list().size} encrypted files")
        appendLine("Config store:  ${config.listConfigs().size} configs")
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun verifyAppIntegrityAsync() {
        scope.launch {
            val ok = depVerifier.verifyAppSignature()
            if (!ok) Log.e(TAG, "App signature verification failed!")
        }
    }

    private fun scheduleSecretRotationCheck() {
        scope.launch {
            // In production, provide actual rotation providers per key
            Log.d(TAG, "Secret rotation check: no keys registered for auto-rotation")
        }
    }
}
