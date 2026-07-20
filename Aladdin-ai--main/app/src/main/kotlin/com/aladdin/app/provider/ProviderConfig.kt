package com.aladdin.app.provider

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProviderConfig — Centralised API provider config.
 * Gemini is the only cloud LLM backend. On-device llama.cpp is available as
 * an opt-in offline fallback (`preferredProvider = "local"`).
 *
 * All Ollama / OpenAI-compatible config has been removed.
 */
@Singleton
class ProviderConfig @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // ── Gemini ────────────────────────────────────────────────────────────────
    var geminiApiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(v) { prefs.edit { putString("gemini_key", v) }; Log.i(TAG, "Gemini key updated") }

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-1.5-flash") ?: "gemini-1.5-flash"
        set(v) { prefs.edit { putString("gemini_model", v) } }

    var geminiBaseUrl: String
        get() = prefs.getString("gemini_url", "https://generativelanguage.googleapis.com/v1beta") ?: ""
        set(v) { prefs.edit { putString("gemini_url", v) } }

    val isGeminiConfigured get() = geminiApiKey.isNotBlank()

    // ── OpenAI (kept for optional future use, not wired to voice pipeline) ────
    var openAiApiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(v) { prefs.edit { putString("openai_key", v) }; Log.i(TAG, "OpenAI key updated") }

    var openAiModel: String
        get() = prefs.getString("openai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) { prefs.edit { putString("openai_model", v) } }

    var openAiBaseUrl: String
        get() = prefs.getString("openai_url", "https://api.openai.com/v1") ?: ""
        set(v) { prefs.edit { putString("openai_url", v) } }

    val isOpenAiConfigured get() = openAiApiKey.isNotBlank()

    // ── Anthropic (optional) ──────────────────────────────────────────────────
    var anthropicApiKey: String
        get() = prefs.getString("anthropic_key", "") ?: ""
        set(v) { prefs.edit { putString("anthropic_key", v) }; Log.i(TAG, "Anthropic key updated") }

    var anthropicModel: String
        get() = prefs.getString("anthropic_model", "claude-3-haiku-20240307") ?: "claude-3-haiku-20240307"
        set(v) { prefs.edit { putString("anthropic_model", v) } }

    val isAnthropicConfigured get() = anthropicApiKey.isNotBlank()

    // ── Preferred provider ────────────────────────────────────────────────────
    // "gemini" = use Gemini cloud API (default, requires API key in Settings)
    // "local"  = use on-device llama.cpp (fully offline, opt-in)
    var preferredProvider: String
        get() = prefs.getString("pref_provider", "gemini") ?: "gemini"
        set(v) { prefs.edit { putString("pref_provider", v) } }

    // ── Telegram ──────────────────────────────────────────────────────────────
    var telegramBotToken: String
        get() = prefs.getString("tg_token", "") ?: ""
        set(v) { prefs.edit { putString("tg_token", v) }; Log.i(TAG, "Telegram token updated") }

    var telegramDefaultChatId: String
        get() = prefs.getString("tg_chat_id", "") ?: ""
        set(v) { prefs.edit { putString("tg_chat_id", v) } }

    val isTelegramConfigured get() = telegramBotToken.isNotBlank()

    // ── Discord ───────────────────────────────────────────────────────────────
    var discordBotToken: String
        get() = prefs.getString("discord_token", "") ?: ""
        set(v) { prefs.edit { putString("discord_token", v) }; Log.i(TAG, "Discord token updated") }

    var discordDefaultChannelId: String
        get() = prefs.getString("discord_channel", "") ?: ""
        set(v) { prefs.edit { putString("discord_channel", v) } }

    val isDiscordConfigured get() = discordBotToken.isNotBlank()

    // ── SMTP ──────────────────────────────────────────────────────────────────
    var smtpHost: String
        get() = prefs.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com"
        set(v) { prefs.edit { putString("smtp_host", v) } }

    var smtpPort: Int
        get() = prefs.getInt("smtp_port", 587)
        set(v) { prefs.edit { putInt("smtp_port", v) } }

    var smtpUser: String
        get() = prefs.getString("smtp_user", "") ?: ""
        set(v) { prefs.edit { putString("smtp_user", v) }; Log.i(TAG, "SMTP user updated") }

    var smtpPassword: String
        get() = prefs.getString("smtp_pass", "") ?: ""
        set(v) { prefs.edit { putString("smtp_pass", v) } }

    var smtpUseTls: Boolean
        get() = prefs.getBoolean("smtp_tls", true)
        set(v) { prefs.edit { putBoolean("smtp_tls", v) } }

    val isSmtpConfigured get() = smtpUser.isNotBlank() && smtpPassword.isNotBlank()

    fun getConfiguredProviders() = buildList {
        add("gemini")  // default — requires API key in Settings
        add("local")   // always available as offline fallback
        if (isOpenAiConfigured) add("openai")
        if (isAnthropicConfigured) add("anthropic")
    }

    fun logConfig() = Log.i(TAG, "Providers:${getConfiguredProviders()} SMTP:$isSmtpConfigured Tg:$isTelegramConfigured Discord:$isDiscordConfigured")

    companion object { private const val TAG = "ProviderConfig" }
}
