package com.aladdin.security.config

import android.util.Base64
import com.aladdin.security.secrets.KeystoreManager
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Encrypts / decrypts configuration values and computes HMAC-style integrity tags.
 */
object ConfigEncryptor {

    private const val KEY_ALIAS    = "aladdin_config_key"
    private const val HMAC_ALIAS   = "aladdin_config_hmac"
    private const val INTEGRITY_KEY = "___integrity"

    /** Encrypt every string value in a JSON object; returns Base64-wrapped ciphertext JSON */
    fun encryptConfig(json: JSONObject): String {
        val encrypted = JSONObject()
        json.keys().forEach { key ->
            val value = json.getString(key)
            val encBytes = KeystoreManager.encrypt(KEY_ALIAS, value.toByteArray(Charsets.UTF_8))
            encrypted.put(key, Base64.encodeToString(encBytes, Base64.NO_WRAP))
        }
        // Attach integrity tag
        val hash = computeIntegrity(encrypted.toString())
        encrypted.put(INTEGRITY_KEY, hash)
        return encrypted.toString()
    }

    /** Decrypt a config string produced by [encryptConfig] */
    fun decryptConfig(encryptedJson: String): JSONObject {
        val obj = JSONObject(encryptedJson)
        val storedHash = obj.optString(INTEGRITY_KEY, "")
        val withoutHash = JSONObject(encryptedJson).also { it.remove(INTEGRITY_KEY) }

        if (storedHash.isNotEmpty()) {
            val expected = computeIntegrity(withoutHash.toString())
            require(storedHash == expected) { "Config integrity check FAILED — data may be tampered" }
        }

        val decrypted = JSONObject()
        withoutHash.keys().forEach { key ->
            val encBytes = Base64.decode(withoutHash.getString(key), Base64.NO_WRAP)
            val plain = String(KeystoreManager.decrypt(KEY_ALIAS, encBytes), Charsets.UTF_8)
            decrypted.put(key, plain)
        }
        return decrypted
    }

    fun computeIntegrity(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
}
