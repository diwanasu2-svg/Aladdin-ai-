package com.aladdin.app.provider

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProviderConfig — Items 20-26: Centralised API provider config.
 * Item 20: SMTP  Item 21: Telegram  Item 22: Discord
 * Item 23: Gemini  Item 24: OpenAI  Item 25: Anthropic  Item 26: Ollama
 * All values in encrypted SharedPreferences — never hardcoded.
 */
@Singleton
class ProviderConfig @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // Item 23: Gemini
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

    // Item 24: OpenAI
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

    // Item 25: Anthropic
    var anthropicApiKey: String
        get() = prefs.getString("anthropic_key", "") ?: ""
        set(v) { prefs.edit { putString("anthropic_key", v) }; Log.i(TAG, "Anthropic key updated") }
    var anthropicModel: String
        get() = prefs.getString("anthropic_model", "claude-3-haiku-20240307") ?: "claude-3-haiku-20240307"
        set(v) { prefs.edit { putString("anthropic_model", v) } }
    val isAnthropicConfigured get() = anthropicApiKey.isNotBlank()

    // Item 26: Ollama
    var ollamaHost: String
        get() = prefs.getString("ollama_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) { prefs.edit { putString("ollama_host", v) } }
    var ollamaPort: Int
        get() = prefs.getInt("ollama_port", 11434)
        set(v) { prefs.edit { putInt("ollama_port", v) } }
    var ollamaModel: String
        get() = prefs.getString("ollama_model", "llama3.2:3b") ?: "llama3.2:3b"
        set(v) { prefs.edit { putString("ollama_model", v) } }
    val ollamaBaseUrl get() = "http://${ollamaHost}:${ollamaPort}"
    val isOllamaConfigured get() = ollamaHost.isNotBlank()

    // Item 21: Telegram
    var telegramBotToken: String
        get() = prefs.getString("tg_token", "") ?: ""
        set(v) { prefs.edit { putString("tg_token", v) }; Log.i(TAG, "Telegram token updated") }
    var telegramDefaultChatId: String
        get() = prefs.getString("tg_chat_id", "") ?: ""
        set(v) { prefs.edit { putString("tg_chat_id", v) } }
    val isTelegramConfigured get() = telegramBotToken.isNotBlank()

    // Item 22: Discord
    var discordBotToken: String
        get() = prefs.getString("discord_token", "") ?: ""
        set(v) { prefs.edit { putString("discord_token", v) }; Log.i(TAG, "Discord token updated") }
    var discordDefaultChannelId: String
        get() = prefs.getString("discord_channel", "") ?: ""
        set(v) { prefs.edit { putString("discord_channel", v) } }
    val isDiscordConfigured get() = discordBotToken.isNotBlank()

    // Item 20: SMTP
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

    // Item 27b: default provider. Reverted (2026-07-08) back to using an
    // external Ollama server — the bundled on-device llama.cpp engine was
    // too slow / hung on this device's hardware. Point `ollamaHost` /
    // `ollamaPort` (Settings screen) at wherever your Ollama server runs
    // (127.0.0.1 if it's on the same phone via PRoot/Termux with
    // `ollama serve`, or a LAN/remote IP for a PC/cloud server). Users can
    // still opt into Gemini (add an API key), or explicitly switch this
    // back to "local" to use the fully offline on-device engine instead.
    var preferredProvider: String
        get() = prefs.getString("pref_provider", "ollama") ?: "ollama"
        set(v) { prefs.edit { putString("pref_provider", v) } }

    fun getConfiguredProviders() = buildList {
        add("ollama") // default path — requires an Ollama server reachable at ollamaHost:ollamaPort
        add("local") // always available as a fallback — bundled on-device model
        if (isGeminiConfigured) add("gemini")
        if (isOpenAiConfigured) add("openai")
        if (isAnthropicConfigured) add("anthropic")
    }

    fun logConfig() = Log.i(TAG, "Providers:${getConfiguredProviders()} SMTP:$isSmtpConfigured Tg:$isTelegramConfigured Discord:$isDiscordConfigured")

    companion object { private const val TAG = "ProviderConfig" }
}
