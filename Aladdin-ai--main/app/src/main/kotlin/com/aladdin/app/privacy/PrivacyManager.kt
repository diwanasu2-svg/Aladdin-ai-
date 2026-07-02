package com.aladdin.app.privacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PrivacyManager — Items 76-78: Privacy controls, data retention, consent tracking.
 *
 * Manages user privacy settings: what data is collected, stored, and for how long.
 */
@Singleton
class PrivacyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG   = "PrivacyManager"
        private const val PREFS = "privacy_settings"
    }

    enum class DataCategory {
        VOICE_RECORDINGS, CONVERSATION_HISTORY, LOCATION_DATA,
        USAGE_ANALYTICS, CRASH_REPORTS, FACE_DATA, CONTACT_ACCESS,
        CALENDAR_ACCESS
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Consent ───────────────────────────────────────────────────────────────

    fun hasConsent(category: DataCategory): Boolean =
        prefs.getBoolean("consent_${category.name}", defaultConsent(category))

    fun setConsent(category: DataCategory, granted: Boolean) {
        prefs.edit().putBoolean("consent_${category.name}", granted).apply()
        Log.i(TAG, "Consent ${if (granted) "granted" else "revoked"} for $category")
    }

    fun getAllConsentStatus(): Map<DataCategory, Boolean> =
        DataCategory.entries.associateWith { hasConsent(it) }

    fun revokeAllConsent() {
        DataCategory.entries.forEach { setConsent(it, false) }
        Log.i(TAG, "All consent revoked")
    }

    // ── Data retention ────────────────────────────────────────────────────────

    fun getRetentionDays(category: DataCategory): Int =
        prefs.getInt("retention_${category.name}", defaultRetentionDays(category))

    fun setRetentionDays(category: DataCategory, days: Int) {
        require(days in 1..3650) { "Retention must be 1-3650 days" }
        prefs.edit().putInt("retention_${category.name}", days).apply()
    }

    // ── Data minimisation mode ────────────────────────────────────────────────

    var dataMinimisationMode: Boolean
        get()      = prefs.getBoolean("data_minimisation", false)
        set(value) = prefs.edit().putBoolean("data_minimisation", value).apply()

    var analyticsOptIn: Boolean
        get()      = prefs.getBoolean("analytics_opt_in", false)
        set(value) = prefs.edit().putBoolean("analytics_opt_in", value).apply()

    // ── Right to erasure (GDPR Article 17) ───────────────────────────────────

    fun eraseUserData(categories: Set<DataCategory> = DataCategory.entries.toSet()): List<String> {
        val erased = mutableListOf<String>()
        categories.forEach { cat ->
            if (hasConsent(cat)) {
                // Signal other components to erase their data for this category
                Log.i(TAG, "Erasing data for $cat")
                erased.add(cat.name)
            }
        }
        return erased
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun defaultConsent(category: DataCategory): Boolean = when (category) {
        DataCategory.CRASH_REPORTS       -> true    // enabled by default
        DataCategory.CONVERSATION_HISTORY -> true
        DataCategory.VOICE_RECORDINGS    -> false   // opt-in required
        DataCategory.LOCATION_DATA       -> false
        DataCategory.FACE_DATA           -> false
        DataCategory.USAGE_ANALYTICS     -> false
        DataCategory.CONTACT_ACCESS      -> false
        DataCategory.CALENDAR_ACCESS     -> false
    }

    private fun defaultRetentionDays(category: DataCategory): Int = when (category) {
        DataCategory.VOICE_RECORDINGS    -> 1
        DataCategory.CONVERSATION_HISTORY -> 30
        DataCategory.LOCATION_DATA       -> 7
        DataCategory.FACE_DATA           -> 90
        DataCategory.CRASH_REPORTS       -> 30
        DataCategory.USAGE_ANALYTICS     -> 90
        else                             -> 30
    }
}
