package com.aladdin.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 12 Item 5: Android Keystore Integration — AES-GCM + key management ─

@Singleton
class KeystoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG            = "KeystoreManager"
        private const val KEYSTORE_ALIAS = "aladdin_master_key"
        private const val PROVIDER       = "AndroidKeyStore"
        private const val ALGORITHM      = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH  = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    // ── Key generation ────────────────────────────────────────────────────────
    fun generateKey(alias: String = KEYSTORE_ALIAS, requireUserAuth: Boolean = false) {
        if (keyStore.containsAlias(alias)) {
            Log.d(TAG, "Key '$alias' already exists — skipping generation")
            return
        }
        try {
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(requireUserAuth)
                .build()

            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
                init(spec)
                generateKey()
            }
            Log.i(TAG, "AES-256-GCM key generated for alias '$alias'")
        } catch (e: Exception) {
            Log.e(TAG, "generateKey failed: ${e.message}")
            throw e
        }
    }

    // ── Encrypt data ──────────────────────────────────────────────────────────
    fun encrypt(plaintext: String, alias: String = KEYSTORE_ALIAS): String {
        generateKey(alias)
        return try {
            val key    = getSecretKey(alias)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv         = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // Prepend IV to ciphertext
            val combined = iv + ciphertext
            Base64.encodeToString(combined, Base64.NO_WRAP).also {
                Log.d(TAG, "Encrypted ${plaintext.length} chars with alias '$alias'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed: ${e.message}")
            throw e
        }
    }

    // ── Decrypt data ──────────────────────────────────────────────────────────
    fun decrypt(encryptedData: String, alias: String = KEYSTORE_ALIAS): String {
        return try {
            val combined   = Base64.decode(encryptedData, Base64.NO_WRAP)
            val iv         = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val key    = getSecretKey(alias)
            val cipher = Cipher.getInstance(ALGORITHM)
            val spec   = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8).also {
                Log.d(TAG, "Decrypted ${it.length} chars with alias '$alias'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed: ${e.message}")
            throw e
        }
    }

    // ── Encrypt/decrypt byte arrays ───────────────────────────────────────────
    fun encryptBytes(data: ByteArray, alias: String = KEYSTORE_ALIAS): ByteArray {
        generateKey(alias)
        val key    = getSecretKey(alias)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        return iv + ct
    }

    fun decryptBytes(data: ByteArray, alias: String = KEYSTORE_ALIAS): ByteArray {
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ct = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val key    = getSecretKey(alias)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ct)
    }

    // ── Key management ────────────────────────────────────────────────────────
    fun deleteKey(alias: String = KEYSTORE_ALIAS) {
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
                Log.i(TAG, "Key '$alias' deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteKey failed: ${e.message}")
        }
    }

    fun rotateKey(alias: String = KEYSTORE_ALIAS, existingData: List<String>): List<String> {
        Log.i(TAG, "Rotating key '$alias'")
        // Decrypt all data with old key
        val decrypted = existingData.map { decrypt(it, alias) }
        // Delete old key
        deleteKey(alias)
        // Generate new key
        generateKey(alias)
        // Re-encrypt with new key
        return decrypted.map { encrypt(it, alias) }.also {
            Log.i(TAG, "Key rotation complete — re-encrypted ${it.size} items")
        }
    }

    fun listAliases(): List<String> = keyStore.aliases().toList()

    fun keyExists(alias: String = KEYSTORE_ALIAS): Boolean = keyStore.containsAlias(alias)

    private fun getSecretKey(alias: String): SecretKey =
        (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
}
