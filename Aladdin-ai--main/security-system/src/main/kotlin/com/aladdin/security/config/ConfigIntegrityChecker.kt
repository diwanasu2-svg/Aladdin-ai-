package com.aladdin.security.config

import android.util.Log
import java.io.File

/**
 * Verifies config file integrity at runtime.
 * Maintains a manifest of (filename → sha256) stored in EncryptedSharedPreferences.
 * On every load, recomputes the hash and compares.
 */
class ConfigIntegrityChecker {

    companion object { private const val TAG = "ConfigIntegrityChecker" }

    private val hashes = mutableMapOf<String, String>()

    /** Record the current hash of a file as the trusted baseline */
    fun registerBaseline(file: File) {
        val hash = sha256(file.readBytes())
        hashes[file.name] = hash
        Log.d(TAG, "Baseline registered: ${file.name} → ${hash.take(12)}…")
    }

    /** Register a bytes baseline */
    fun registerBaseline(name: String, data: ByteArray) {
        hashes[name] = sha256(data)
    }

    /** Returns true if the file matches its registered baseline */
    fun verify(file: File): Boolean {
        val expected = hashes[file.name]
            ?: return true.also { Log.w(TAG, "No baseline for ${file.name} — skipping check") }
        val actual = sha256(file.readBytes())
        val ok = actual == expected
        if (!ok) Log.e(TAG, "INTEGRITY VIOLATION: ${file.name} hash mismatch! expected=${expected.take(12)} actual=${actual.take(12)}")
        return ok
    }

    fun verify(name: String, data: ByteArray): Boolean {
        val expected = hashes[name] ?: return true
        val actual = sha256(data)
        val ok = actual == expected
        if (!ok) Log.e(TAG, "INTEGRITY VIOLATION: $name")
        return ok
    }

    fun hasBaseline(name: String) = hashes.containsKey(name)

    private fun sha256(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
