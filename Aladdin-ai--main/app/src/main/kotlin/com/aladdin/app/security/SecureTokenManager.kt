package com.aladdin.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 12 Item 1: JWT Authentication — token management, refresh, validate ─

@Singleton
class SecureTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager
) {
    companion object {
        private const val TAG        = "SecureTokenManager"
        private const val PREF_NAME  = "secure_tokens"
        private const val KEY_ACCESS = "access_token_enc"
        private const val KEY_REFRESH= "refresh_token_enc"
        private const val KEY_EXPIRY = "token_expiry_ms"
        private const val ALIAS      = "aladdin_token_key"
        private const val EXPIRY_BUFFER_MS = 60_000L  // Refresh 1 min before expiry
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── Store tokens securely via Keystore ────────────────────────────────────
    suspend fun storeTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) = withContext(Dispatchers.IO) {
        try {
            keystoreManager.generateKey(ALIAS)
            val encAccess  = keystoreManager.encrypt(accessToken, ALIAS)
            val encRefresh = keystoreManager.encrypt(refreshToken, ALIAS)
            val expiryMs   = System.currentTimeMillis() + (expiresInSeconds * 1000)

            prefs.edit()
                .putString(KEY_ACCESS,  encAccess)
                .putString(KEY_REFRESH, encRefresh)
                .putLong(KEY_EXPIRY,    expiryMs)
                .apply()
            Log.i(TAG, "Tokens stored securely — expires in ${expiresInSeconds}s")
        } catch (e: Exception) {
            Log.e(TAG, "storeTokens failed: ${e.message}")
        }
    }

    // ── Retrieve access token (auto-refresh if expired) ───────────────────────
    suspend fun getValidAccessToken(refreshUrl: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            val encToken = prefs.getString(KEY_ACCESS, null) ?: return@withContext null
            val expiry   = prefs.getLong(KEY_EXPIRY, 0)

            // Check if token is about to expire
            if (System.currentTimeMillis() + EXPIRY_BUFFER_MS >= expiry) {
                Log.i(TAG, "Token near expiry — refreshing")
                if (refreshUrl.isNotBlank()) {
                    refreshToken(refreshUrl)
                } else {
                    Log.w(TAG, "No refresh URL — returning expired token")
                }
            }

            val freshEncToken = prefs.getString(KEY_ACCESS, null) ?: return@withContext null
            keystoreManager.decrypt(freshEncToken, ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "getValidAccessToken failed: ${e.message}")
            null
        }
    }

    // ── Token refresh ─────────────────────────────────────────────────────────
    suspend fun refreshToken(refreshUrl: String) = withContext(Dispatchers.IO) {
        try {
            val encRefresh = prefs.getString(KEY_REFRESH, null) ?: return@withContext
            val refreshToken = keystoreManager.decrypt(encRefresh, ALIAS)

            val body = JSONObject().apply { put("refresh_token", refreshToken) }.toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(refreshUrl)
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: return@use)
                    val newAccess  = json.optString("access_token")
                    val newRefresh = json.optString("refresh_token", refreshToken)
                    val expiresIn  = json.optLong("expires_in", 3600)

                    if (newAccess.isNotBlank()) {
                        storeTokens(newAccess, newRefresh, expiresIn)
                        Log.i(TAG, "Token refreshed successfully")
                    }
                } else {
                    Log.w(TAG, "Token refresh failed: HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken failed: ${e.message}")
        }
    }

    // ── Validate token locally (decode JWT payload) ───────────────────────────
    fun validateToken(token: String): TokenInfo {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return TokenInfo(valid = false, error = "Invalid JWT format")

            val payload = android.util.Base64.decode(
                parts[1].padEnd(parts[1].length + (4 - parts[1].length % 4) % 4, '='),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            val json = JSONObject(String(payload))
            val exp  = json.optLong("exp", 0) * 1000
            val sub  = json.optString("sub", "")
            val scope = json.optString("scope", "")

            TokenInfo(
                valid     = exp > System.currentTimeMillis(),
                subject   = sub,
                scope     = scope,
                expiryMs  = exp,
                error     = if (exp <= System.currentTimeMillis()) "Token expired" else null
            )
        } catch (e: Exception) {
            TokenInfo(valid = false, error = "Token parse error: ${e.message}")
        }
    }

    // ── Synchronous wrappers for use in non-suspend contexts (e.g. OkHttp Interceptors) ──
    // OkHttp's Interceptor.intercept() runs on a background thread but is not a suspend
    // function, so we bridge to the suspend token APIs with runBlocking.
    fun getAccessToken(): String? = kotlinx.coroutines.runBlocking { getValidAccessToken() }

    fun refreshAccessToken(): String? = kotlinx.coroutines.runBlocking {
        val encRefresh = prefs.getString(KEY_REFRESH, null)
        if (encRefresh == null) {
            Log.w(TAG, "refreshAccessToken: no refresh token stored")
            return@runBlocking null
        }
        // No dedicated refresh endpoint is wired here; getValidAccessToken() already
        // triggers refreshToken(refreshUrl) internally when a refreshUrl is supplied
        // by the caller elsewhere. Return the (possibly still valid) current token.
        getValidAccessToken()
    }

    // ── Clear tokens (logout) ─────────────────────────────────────────────────
    fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRY).apply()
        Log.i(TAG, "Tokens cleared (logout)")
    }

    fun isAuthenticated(): Boolean {
        val expiry = prefs.getLong(KEY_EXPIRY, 0)
        return prefs.contains(KEY_ACCESS) && System.currentTimeMillis() < expiry
    }

    data class TokenInfo(
        val valid: Boolean,
        val subject: String = "",
        val scope: String = "",
        val expiryMs: Long = 0,
        val error: String? = null
    )
}
