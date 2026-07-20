package com.aladdin.app.provider

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProviderManager — Provider Auto-Switch
 *
 * Gemini is the default and primary LLM provider.
 * On-device llama.cpp and MLC are available as local fallbacks.
 * Ollama has been removed.
 */
@Singleton
class ProviderManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProviderManager"
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val LATENCY_THRESHOLD_MS = 10_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
    }

    enum class ProviderType {
        LLAMA_CPP, MLC_LLM, GEMINI, OPENAI, ANTHROPIC
    }

    data class ProviderConfig(
        val type: ProviderType,
        val displayName: String,
        val isLocal: Boolean,
        val requiresApiKey: Boolean,
        val defaultModel: String,
        var isEnabled: Boolean = true,
        var apiKey: String = "",
        var endpoint: String = "",
        var model: String = defaultModel,
        var timeoutMs: Long = 30_000L
    )

    data class ProviderHealth(
        val type: ProviderType,
        var consecutiveErrors: Int = 0,
        var lastErrorMs: Long = 0L,
        var avgLatencyMs: Long = 0L,
        var isAvailable: Boolean = true,
        var lastCheckedMs: Long = 0L
    )

    private val providers = mutableMapOf<ProviderType, ProviderConfig>()
    private val health    = mutableMapOf<ProviderType, ProviderHealth>()

    private val _activeProvider = MutableStateFlow(ProviderType.GEMINI)
    val activeProvider: StateFlow<ProviderType> = _activeProvider.asStateFlow()

    private val _providerEvents = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 16)
    val providerEvents: SharedFlow<ProviderEvent> = _providerEvents.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        registerDefaultProviders()
        loadSavedConfig()
        startHealthMonitor()
    }

    private fun registerDefaultProviders() {
        listOf(
            ProviderConfig(ProviderType.LLAMA_CPP, "llama.cpp (Local)",    isLocal = true,  requiresApiKey = false, defaultModel = "llama-3.2-3b-instruct.Q4_K_M.gguf"),
            ProviderConfig(ProviderType.MLC_LLM,   "MLC LLM (GPU)",        isLocal = true,  requiresApiKey = false, defaultModel = "Llama-3.2-1B-Instruct-q4f16_1"),
            ProviderConfig(ProviderType.GEMINI,    "Google Gemini",        isLocal = false, requiresApiKey = true,  defaultModel = "gemini-1.5-flash", endpoint = "https://generativelanguage.googleapis.com"),
            ProviderConfig(ProviderType.OPENAI,    "OpenAI",               isLocal = false, requiresApiKey = true,  defaultModel = "gpt-4o-mini",       endpoint = "https://api.openai.com"),
            ProviderConfig(ProviderType.ANTHROPIC, "Anthropic Claude",     isLocal = false, requiresApiKey = true,  defaultModel = "claude-3-5-haiku-20241022", endpoint = "https://api.anthropic.com")
        ).forEach { cfg ->
            providers[cfg.type] = cfg
            health[cfg.type]    = ProviderHealth(cfg.type)
        }
        Log.i(TAG, "Registered ${providers.size} providers")
    }

    private fun loadSavedConfig() {
        val prefs = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)
        val savedProvider = prefs.getString("active_provider", null)
            ?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() }
        if (savedProvider != null && providers.containsKey(savedProvider)) {
            _activeProvider.value = savedProvider
        }
        providers.forEach { (type, cfg) ->
            val key = type.name.lowercase()
            cfg.apiKey   = prefs.getString("${key}_api_key",  cfg.apiKey)   ?: ""
            cfg.endpoint = prefs.getString("${key}_endpoint", cfg.endpoint) ?: cfg.endpoint
            cfg.model    = prefs.getString("${key}_model",    cfg.model)    ?: cfg.defaultModel
            cfg.isEnabled= prefs.getBoolean("${key}_enabled", cfg.isEnabled)
        }
        Log.d(TAG, "Config loaded — active=${_activeProvider.value}")
    }

    fun getActiveConfig(): ProviderConfig = providers[_activeProvider.value]
        ?: providers.values.first()

    fun getAllProviders(): List<ProviderConfig> = providers.values.toList()

    fun getProviderConfig(type: ProviderType): ProviderConfig? = providers[type]

    fun setActiveProvider(type: ProviderType) {
        if (!providers.containsKey(type)) { Log.w(TAG, "Unknown provider: $type"); return }
        _activeProvider.value = type
        saveConfig()
        Log.i(TAG, "Active provider set to $type")
        scope.launch { _providerEvents.emit(ProviderEvent.SwitchedTo(type)) }
    }

    fun updateConfig(type: ProviderType, apiKey: String? = null, endpoint: String? = null, model: String? = null, enabled: Boolean? = null) {
        val cfg = providers[type] ?: return
        apiKey?.let   { cfg.apiKey   = it }
        endpoint?.let { cfg.endpoint = it }
        model?.let    { cfg.model    = it }
        enabled?.let  { cfg.isEnabled = it }
        saveConfig()
        Log.d(TAG, "Updated config for $type")
    }

    fun reportError(type: ProviderType, error: Throwable) {
        val h = health[type] ?: return
        h.consecutiveErrors++
        h.lastErrorMs = System.currentTimeMillis()
        Log.w(TAG, "Provider $type error #${h.consecutiveErrors}: ${error.message}")
        if (h.consecutiveErrors >= MAX_CONSECUTIVE_ERRORS && type == _activeProvider.value) {
            scope.launch { autoSwitch(reason = "Too many errors ($type)") }
        }
    }

    fun reportSuccess(type: ProviderType, latencyMs: Long) {
        val h = health[type] ?: return
        h.consecutiveErrors = 0
        h.avgLatencyMs = (h.avgLatencyMs * 3 + latencyMs) / 4
        h.isAvailable = true
        if (latencyMs > LATENCY_THRESHOLD_MS && type == _activeProvider.value) {
            scope.launch { autoSwitch(reason = "High latency ($latencyMs ms)") }
        }
    }

    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.activeNetworkInfo?.isConnected == true
        } catch (_: Exception) { false }
    }

    private suspend fun autoSwitch(reason: String) {
        val current = _activeProvider.value
        val fallback = findFallback(current)
        if (fallback != null) {
            Log.i(TAG, "Auto-switching from $current to $fallback — $reason")
            _activeProvider.value = fallback
            saveConfig()
            _providerEvents.emit(ProviderEvent.AutoSwitched(from = current, to = fallback, reason = reason))
        } else {
            Log.e(TAG, "No fallback available — staying on $current")
            _providerEvents.emit(ProviderEvent.NoFallbackAvailable(current))
        }
    }

    private fun findFallback(exclude: ProviderType): ProviderType? {
        val online = isOnline()
        val ordered = if (online)
            listOf(ProviderType.GEMINI, ProviderType.OPENAI, ProviderType.ANTHROPIC, ProviderType.LLAMA_CPP, ProviderType.MLC_LLM)
        else
            listOf(ProviderType.LLAMA_CPP, ProviderType.MLC_LLM)
        return ordered.firstOrNull { type ->
            type != exclude &&
            providers[type]?.isEnabled == true &&
            (health[type]?.isAvailable != false) &&
            (providers[type]?.requiresApiKey == false || providers[type]?.apiKey?.isNotBlank() == true)
        }
    }

    private fun startHealthMonitor() {
        scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                checkProviderHealth()
            }
        }
    }

    private fun checkProviderHealth() {
        val now = System.currentTimeMillis()
        health.forEach { (_, h) ->
            if (h.consecutiveErrors > 0 && now - h.lastErrorMs > 5 * 60_000L) {
                h.consecutiveErrors = 0
                h.isAvailable = true
            }
            h.lastCheckedMs = now
        }
    }

    private fun saveConfig() {
        val prefs = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("active_provider", _activeProvider.value.name)
            providers.forEach { (type, cfg) ->
                val key = type.name.lowercase()
                putString("${key}_api_key",  cfg.apiKey)
                putString("${key}_endpoint", cfg.endpoint)
                putString("${key}_model",    cfg.model)
                putBoolean("${key}_enabled", cfg.isEnabled)
            }
        }.apply()
    }

    sealed class ProviderEvent {
        data class SwitchedTo(val type: ProviderType) : ProviderEvent()
        data class AutoSwitched(val from: ProviderType, val to: ProviderType, val reason: String) : ProviderEvent()
        data class NoFallbackAvailable(val type: ProviderType) : ProviderEvent()
    }
}
