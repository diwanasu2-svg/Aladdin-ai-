package com.aladdin.security.secrets

import android.content.Context
import android.util.Log

/**
 * Automated secret rotation.
 * Stores version metadata alongside each secret; rotates when version is stale or on demand.
 * Rotation is atomic: new value is stored before old is deleted.
 */
class SecretRotation(private val secretManager: SecretManager) {

    companion object {
        private const val TAG = "SecretRotation"
        private const val VERSION_SUFFIX = "__version"
        private const val ROTATED_AT_SUFFIX = "__rotated_at"
    }

    data class RotationResult(
        val key: String, val previousVersion: Int, val newVersion: Int, val success: Boolean
    )

    /**
     * Rotate [key] by calling [newValueProvider] to obtain the new value.
     * The provider receives the current version number.
     */
    fun rotate(key: String, newValueProvider: (currentVersion: Int) -> String): RotationResult {
        val currentVersion = secretManager.retrieveOrNull("$key$VERSION_SUFFIX")?.toIntOrNull() ?: 0
        Log.i(TAG, "Rotating secret '${key.take(4)}****' from version $currentVersion")

        return try {
            val newValue = newValueProvider(currentVersion)
            val newVersion = currentVersion + 1

            // Atomic: write new before removing old
            secretManager.store(key, newValue)
            secretManager.store("$key$VERSION_SUFFIX", newVersion.toString())
            secretManager.store("$key$ROTATED_AT_SUFFIX", System.currentTimeMillis().toString())

            Log.i(TAG, "Rotation complete: '${key.take(4)}****' v$currentVersion → v$newVersion")
            RotationResult(key, currentVersion, newVersion, true)
        } catch (e: Exception) {
            Log.e(TAG, "Rotation failed for '${key.take(4)}****'", e)
            RotationResult(key, currentVersion, currentVersion, false)
        }
    }

    fun getVersion(key: String): Int =
        secretManager.retrieveOrNull("$key$VERSION_SUFFIX")?.toIntOrNull() ?: 0

    fun getRotatedAt(key: String): Long =
        secretManager.retrieveOrNull("$key$ROTATED_AT_SUFFIX")?.toLongOrNull() ?: 0L

    fun needsRotation(key: String, maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000): Boolean {
        val rotatedAt = getRotatedAt(key)
        if (rotatedAt == 0L) return false    // never rotated = new, not stale
        return System.currentTimeMillis() - rotatedAt > maxAgeMs
    }

    fun rotateAll(keys: List<String>, maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
                  newValueProvider: (key: String, version: Int) -> String): List<RotationResult> {
        return keys.filter { needsRotation(it, maxAgeMs) }
            .map { key -> rotate(key) { version -> newValueProvider(key, version) } }
    }
}
