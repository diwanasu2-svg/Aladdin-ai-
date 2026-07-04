package com.aladdin.app.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProductionHardening — Item 73: Production security hardening.
 * - HTTPS-only enforcement via OkHttp interceptor
 * - EncryptedSharedPreferences for all API keys/secrets
 * - Root detection and developer mode check
 * - Memory wiping of sensitive char/byte arrays
 * - Security audit report
 */
@Singleton
class ProductionHardening @Inject constructor(private val context: Context) {
    companion object {
        private const val TAG = "ProductionHardening"
        // Pinned SHA-256 public key hashes for production endpoints (update on cert rotation)
        val CERT_PINS = mapOf(
            "generativelanguage.googleapis.com" to listOf("sha256/4AtkuGx5OCbBb2Y3MXrLEcTF4HA3TJj8z3X4l5Z7M0c="),
            "api.openai.com" to listOf("sha256/p0lMk6jtJnFTFpUkj+lHJ4ZClkLIBbI0c5mjXA6f0c4="),
            "api.anthropic.com" to listOf("sha256/r/mIkG3eEpbKTLr9KVdTGJxk3W9sNqQJKl7hhLSR4R8=")
        )
    }

    // Encrypted SharedPreferences for API keys
    private var _prefs: android.content.SharedPreferences? = null
    val encryptedPrefs: android.content.SharedPreferences get() {
        if (_prefs == null) {
            _prefs = try {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(context, "aladdin_secure", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedPrefs failed — using plain fallback: ${e.message}")
                context.getSharedPreferences("aladdin_secure_fb", Context.MODE_PRIVATE)
            }
        }
        return _prefs!!
    }

    fun storeApiKey(name: String, key: String) { encryptedPrefs.edit().putString("k_$name", key).apply() }
    fun retrieveApiKey(name: String): String = encryptedPrefs.getString("k_$name", "") ?: ""
    fun deleteApiKey(name: String) { encryptedPrefs.edit().remove("k_$name").apply() }

    // Root detection
    fun isRooted() = listOf("/sbin/su","/system/bin/su","/system/xbin/su","/data/local/bin/su").any { java.io.File(it).exists() }.also { if (it) Log.w(TAG, "Device is rooted") }
    fun isDeveloperMode() = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    // Memory wipe
    fun wipe(data: CharArray) = data.fill('\u0000')
    fun wipe(data: ByteArray) = data.fill(0)

    // HTTPS-only OkHttp client with cert pinning
    fun buildSecureHttpClient(): OkHttpClient {
        val pins = okhttp3.CertificatePinner.Builder().apply { CERT_PINS.forEach { (h, ps) -> ps.forEach { add(h, it) } } }.build()
        return OkHttpClient.Builder()
            .certificatePinner(pins)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val r = chain.request()
                // Allow LAN (Ollama) and localhost; enforce HTTPS for all others
                if (r.url.scheme != "https" && !r.url.host.matches(Regex("(192\\.168|10\\.|172\\.(1[6-9]|2\\d|3[01])).*")) && r.url.host != "localhost")
                    throw IOException("HTTPS required. Blocked cleartext to ${r.url.host}")
                chain.proceed(r)
            }.build()
    }

    // Security audit
    data class AuditResult(val passed: Boolean, val issues: List<String>, val warnings: List<String>)
    fun runAudit(): AuditResult {
        val issues = mutableListOf<String>(); val warnings = mutableListOf<String>()
        if (isRooted()) issues += "Device is rooted"
        if (isDeveloperMode()) warnings += "Developer mode enabled"
        val debug = try { context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 } catch (_: Exception) { false }
        if (debug) warnings += "Debug build — not for production"
        Log.i(TAG, "Audit: ${issues.size} issues, ${warnings.size} warnings")
        return AuditResult(issues.isEmpty(), issues, warnings)
    }
}