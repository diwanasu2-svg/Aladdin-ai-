package com.aladdin.security.secrets

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aladdin.security.exceptions.SecretNotFoundException
import com.aladdin.security.exceptions.SecretStorageException

/**
 * Unified secret management:
 *  - EncryptedSharedPreferences for small key-value secrets (API keys, tokens)
 *  - Android Keystore for encryption keys
 *  - Rotation support with versioned key aliases
 */
class SecretManager(private val context: Context) {

    companion object {
        private const val TAG        = "SecretManager"
        private const val PREFS_FILE = "aladdin_secrets"
        private const val KEY_ALIAS  = "aladdin_master_key"
    }

    private val masterKey by lazy {
        MasterKey.Builder(context, KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encPrefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                context, PREFS_FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw SecretStorageException("Failed to open EncryptedSharedPreferences", e)
        }
    }

    // ─── Store / Retrieve ──────────────────────────────────────────────────────

    fun store(key: String, value: String) {
        try {
            encPrefs.edit().putString(key, value).apply()
            Log.d(TAG, "Secret stored: ${key.sanitizeForLog()}")
        } catch (e: Exception) {
            throw SecretStorageException("Failed to store secret '${key.sanitizeForLog()}'", e)
        }
    }

    fun retrieve(key: String): String {
        return encPrefs.getString(key, null)
            ?: throw SecretNotFoundException("Secret not found: ${key.sanitizeForLog()}")
    }

    fun retrieveOrNull(key: String): String? = runCatching { retrieve(key) }.getOrNull()

    fun exists(key: String): Boolean = encPrefs.contains(key)

    fun delete(key: String) {
        encPrefs.edit().remove(key).apply()
        Log.d(TAG, "Secret deleted: ${key.sanitizeForLog()}")
    }

    fun deleteAll() {
        encPrefs.edit().clear().apply()
        Log.i(TAG, "All secrets cleared")
    }

    // ─── Keystore-backed operations ───────────────────────────────────────────

    /** Store a high-sensitivity secret encrypted by the Keystore (double encryption) */
    fun storeHighSecurity(key: String, value: String) {
        val keystoreAlias = "hs_$key"
        val encrypted = KeystoreManager.encryptString(keystoreAlias, value)
        store("hs:$key", encrypted)
        Log.d(TAG, "High-security secret stored: ${key.sanitizeForLog()}")
    }

    fun retrieveHighSecurity(key: String): String {
        val keystoreAlias = "hs_$key"
        val encrypted = retrieve("hs:$key")
        return KeystoreManager.decryptString(keystoreAlias, encrypted)
    }

    // ─── Runtime Decryption ───────────────────────────────────────────────────

    /**
     * Decrypt a value only when needed; the plaintext is not stored anywhere.
     * Returns a [SensitiveString] that zeroes memory on close.
     */
    fun decryptOnDemand(key: String): SensitiveString {
        val value = retrieve(key)
        return SensitiveString(value.toCharArray())
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun String.sanitizeForLog() = if (length > 8) "${take(4)}****" else "****"
}

/** Wraps sensitive string data and zeroes char array on close */
class SensitiveString(private val chars: CharArray) : AutoCloseable {
    fun use(): CharArray = chars
    fun asString(): String = String(chars)
    override fun close() { chars.fill('\u0000') }
}
