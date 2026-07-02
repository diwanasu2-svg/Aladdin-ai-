package com.aladdin.security.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore wrapper — all cryptographic keys are hardware-backed when available.
 * Uses AES-256-GCM: authenticated encryption with integrity.
 */
object KeystoreManager {

    private const val TAG = "KeystoreManager"
    private const val KEYSTORE   = "AndroidKeyStore"
    private const val ALGORITHM  = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING    = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val TRANSFORM  = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    /** Generate (or retrieve existing) AES-256 key backed by Android Keystore */
    fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).also { it.load(null) }
        val existing = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        Log.i(TAG, "Creating new Keystore key: $alias")
        KeyGenerator.getInstance(ALGORITHM, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(BLOCK_MODE)
                    .setEncryptionPaddings(PADDING)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()

        return (KeyStore.getInstance(KEYSTORE).also { it.load(null) }
            .getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /** Encrypt plaintext bytes; returns IV + ciphertext */
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val key    = getOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv         = cipher.iv         // 12-byte GCM IV
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext             // prepend IV for decryption
    }

    /** Decrypt bytes previously encrypted with [encrypt] */
    fun decrypt(alias: String, data: ByteArray): ByteArray {
        require(data.size > 12) { "Data too short to contain IV" }
        val iv         = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val key    = getOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    /** Encrypt a UTF-8 string; returns Base64 string for storage */
    fun encryptString(alias: String, plaintext: String): String {
        val encrypted = encrypt(alias, plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    /** Decrypt a Base64 string previously encrypted with [encryptString] */
    fun decryptString(alias: String, encrypted: String): String {
        val bytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
        return String(decrypt(alias, bytes), Charsets.UTF_8)
    }

    fun deleteKey(alias: String) {
        KeyStore.getInstance(KEYSTORE).also { it.load(null) }.deleteEntry(alias)
        Log.i(TAG, "Deleted Keystore key: $alias")
    }

    fun keyExists(alias: String): Boolean {
        return KeyStore.getInstance(KEYSTORE).also { it.load(null) }.containsAlias(alias)
    }

    fun listAliases(): List<String> =
        KeyStore.getInstance(KEYSTORE).also { it.load(null) }.aliases().toList()
}
