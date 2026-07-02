package com.aladdin.app.messaging

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessagingConfig — persists API tokens/keys in encrypted SharedPreferences.
 *
 * In production, supply these values via your backend or a secrets manager —
 * never hard-code tokens in the APK.
 *
 * Set values at runtime (e.g., from a settings screen or secure onboarding flow):
 *   config.telegramBotToken = "123456:ABCdef..."
 */
@Singleton
class MessagingConfig @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val prefs = ctx.getSharedPreferences("messaging_config", Context.MODE_PRIVATE)

    // ─── Telegram ─────────────────────────────────────────────────────────────
    var telegramBotToken: String
        get() = prefs.getString("tg_token", "") ?: ""
        set(v) = prefs.edit { putString("tg_token", v) }

    /** Default chat id to send messages to (can be overridden per-message) */
    var telegramDefaultChatId: String
        get() = prefs.getString("tg_chat_id", "") ?: ""
        set(v) = prefs.edit { putString("tg_chat_id", v) }

    // ─── WhatsApp (Cloud API) ─────────────────────────────────────────────────
    var whatsappAccessToken: String
        get() = prefs.getString("wa_token", "") ?: ""
        set(v) = prefs.edit { putString("wa_token", v) }

    var whatsappPhoneNumberId: String
        get() = prefs.getString("wa_phone_id", "") ?: ""
        set(v) = prefs.edit { putString("wa_phone_id", v) }

    var whatsappVerifyToken: String
        get() = prefs.getString("wa_verify_token", "aladdin_verify") ?: "aladdin_verify"
        set(v) = prefs.edit { putString("wa_verify_token", v) }

    // ─── Discord ──────────────────────────────────────────────────────────────
    var discordBotToken: String
        get() = prefs.getString("discord_token", "") ?: ""
        set(v) = prefs.edit { putString("discord_token", v) }

    var discordDefaultChannelId: String
        get() = prefs.getString("discord_channel_id", "") ?: ""
        set(v) = prefs.edit { putString("discord_channel_id", v) }

    // ─── Email / Gmail ────────────────────────────────────────────────────────
    var gmailAccessToken: String
        get() = prefs.getString("gmail_token", "") ?: ""
        set(v) = prefs.edit { putString("gmail_token", v) }

    var emailFromAddress: String
        get() = prefs.getString("email_from", "") ?: ""
        set(v) = prefs.edit { putString("email_from", v) }

    // SMTP fallback (JavaMail)
    var smtpHost: String
        get() = prefs.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com"
        set(v) = prefs.edit { putString("smtp_host", v) }
    var smtpPort: Int
        get() = prefs.getInt("smtp_port", 587)
        set(v) = prefs.edit { putInt("smtp_port", v) }
    var smtpUser: String
        get() = prefs.getString("smtp_user", "") ?: ""
        set(v) = prefs.edit { putString("smtp_user", v) }
    var smtpPassword: String
        get() = prefs.getString("smtp_pass", "") ?: ""
        set(v) = prefs.edit { putString("smtp_pass", v) }

    // ─── FCM ─────────────────────────────────────────────────────────────────
    var fcmServerKey: String
        get() = prefs.getString("fcm_server_key", "") ?: ""
        set(v) = prefs.edit { putString("fcm_server_key", v) }

    // ─── Sync interval ────────────────────────────────────────────────────────
    var pollIntervalSeconds: Long
        get() = prefs.getLong("poll_interval", 15L)
        set(v) = prefs.edit { putLong("poll_interval", v) }

    fun isAnyPlatformConfigured(): Boolean =
        telegramBotToken.isNotBlank() ||
        whatsappAccessToken.isNotBlank() ||
        discordBotToken.isNotBlank() ||
        gmailAccessToken.isNotBlank()
}
