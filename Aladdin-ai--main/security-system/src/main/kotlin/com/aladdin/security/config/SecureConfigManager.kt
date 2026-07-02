package com.aladdin.security.config

import android.content.Context
import android.util.Log
import com.aladdin.security.exceptions.ConfigIntegrityException
import org.json.JSONObject
import java.io.File

/**
 * Encrypted + integrity-checked configuration store.
 *
 * Flow:
 *   write → encrypt entire JSON → store to disk with integrity tag
 *   read  → load from disk → verify integrity → decrypt → return JSONObject
 */
class SecureConfigManager(private val context: Context) {

    companion object {
        private const val TAG      = "SecureConfigManager"
        private const val CONFIG_DIR = "secure_configs"
    }

    private val configDir = File(context.filesDir, CONFIG_DIR).also { it.mkdirs() }
    private val integrityChecker = ConfigIntegrityChecker()

    /** Write a configuration to encrypted storage */
    fun write(name: String, config: JSONObject) {
        val encryptedJson = ConfigEncryptor.encryptConfig(config)
        val file = fileFor(name)
        file.writeText(encryptedJson)
        integrityChecker.registerBaseline(file)
        Log.i(TAG, "Secure config '$name' written (${encryptedJson.length} bytes)")
    }

    /** Read and decrypt a configuration; throws [ConfigIntegrityException] if tampered */
    fun read(name: String): JSONObject {
        val file = fileFor(name)
        require(file.exists()) { "Secure config '$name' not found" }

        if (!integrityChecker.verify(file)) {
            throw ConfigIntegrityException("Config '$name' failed integrity check — possible tampering")
        }

        return try {
            ConfigEncryptor.decryptConfig(file.readText())
        } catch (e: Exception) {
            throw ConfigIntegrityException("Config '$name' decryption failed", e)
        }
    }

    /** Read a single value from a config */
    fun get(name: String, key: String): String? =
        runCatching { read(name).optString(key) }.getOrNull()

    /** Update a single key in an existing config (re-encrypts and re-signs entire file) */
    fun update(name: String, key: String, value: String) {
        val config = if (exists(name)) read(name) else JSONObject()
        config.put(key, value)
        write(name, config)
    }

    fun exists(name: String) = fileFor(name).exists()

    fun delete(name: String) {
        fileFor(name).delete()
        Log.i(TAG, "Secure config '$name' deleted")
    }

    fun listConfigs(): List<String> =
        configDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()

    private fun fileFor(name: String) = File(configDir, "${name.replace(Regex("[^a-zA-Z0-9_]"), "_")}.enc")
}
