package com.aladdin.security.storage

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aladdin.security.exceptions.SecureStorageException

/**
 * Drop-in replacement for SharedPreferences that encrypts all keys and values.
 * Backed by [EncryptedSharedPreferences] from Jetpack Security.
 *
 * Supports: String, Int, Long, Float, Boolean, StringSet
 */
class SecurePreferences(context: Context, name: String = "aladdin_secure_prefs") {

    companion object { private const val TAG = "SecurePreferences" }

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw SecureStorageException("Failed to open SecurePreferences: ${e.javaClass.simpleName}", e)
        }
    }

    fun putString(key: String, value: String)  { prefs.edit().putString(key, value).apply() }
    fun putInt(key: String, value: Int)         { prefs.edit().putInt(key, value).apply() }
    fun putLong(key: String, value: Long)       { prefs.edit().putLong(key, value).apply() }
    fun putFloat(key: String, value: Float)     { prefs.edit().putFloat(key, value).apply() }
    fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun putStringSet(key: String, value: Set<String>) { prefs.edit().putStringSet(key, value).apply() }

    fun getString(key: String, default: String? = null): String? = prefs.getString(key, default)
    fun getInt(key: String, default: Int = 0): Int               = prefs.getInt(key, default)
    fun getLong(key: String, default: Long = 0L): Long           = prefs.getLong(key, default)
    fun getFloat(key: String, default: Float = 0f): Float        = prefs.getFloat(key, default)
    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)
    fun getStringSet(key: String): Set<String>                   = prefs.getStringSet(key, emptySet()) ?: emptySet()

    fun contains(key: String) = prefs.contains(key)
    fun remove(key: String)   { prefs.edit().remove(key).apply() }
    fun clear()               { prefs.edit().clear().apply(); Log.i(TAG, "Secure preferences cleared") }
    fun all(): Map<String, *> = prefs.all
}
