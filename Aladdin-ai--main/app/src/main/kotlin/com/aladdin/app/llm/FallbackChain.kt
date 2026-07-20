package com.aladdin.app.llm

import android.util.Log
import com.aladdin.app.provider.ProviderManager
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FallbackChain — Primary → Backup → Fallback with graceful degradation.
 *
 * Executes a request against providers in priority order, advancing to the next
 * provider on failure, and notifying the user on each switch.
 *
 * Provider order: Gemini → OpenAI → Anthropic → llama.cpp → MLC LLM
 * (Ollama removed — Gemini is the default cloud backend)
 */
@Singleton
class FallbackChain @Inject constructor(
    private val providerManager: ProviderManager
) {
    companion object {
        private const val TAG = "FallbackChain"
    }

    data class ChainResult(
        val response: String,
        val usedProvider: ProviderManager.ProviderType,
        val attemptCount: Int,
        val totalLatencyMs: Long
    )

    private val fallbackOrder = listOf(
        ProviderManager.ProviderType.GEMINI,
        ProviderManager.ProviderType.OPENAI,
        ProviderManager.ProviderType.ANTHROPIC,
        ProviderManager.ProviderType.LLAMA_CPP,
        ProviderManager.ProviderType.MLC_LLM
    )

    suspend fun execute(
        requestFn: suspend (ProviderManager.ProviderConfig) -> String,
        onSwitched: ((from: ProviderManager.ProviderType, to: ProviderManager.ProviderType) -> Unit)? = null
    ): ChainResult {
        val start = System.currentTimeMillis()
        val activeType = providerManager.activeProvider.value

        val chain = buildList {
            add(activeType)
            fallbackOrder.filter { it != activeType && providerManager.getProviderConfig(it)?.isEnabled == true }
                .forEach { add(it) }
        }

        var lastError: Throwable? = null
        for ((attempt, type) in chain.withIndex()) {
            val cfg = providerManager.getProviderConfig(type)
            if (cfg == null || !cfg.isEnabled) continue
            if (cfg.requiresApiKey && cfg.apiKey.isBlank()) {
                Log.w(TAG, "Skipping $type — API key not configured")
                continue
            }
            if (attempt > 0) onSwitched?.invoke(chain[attempt - 1], type)
            try {
                Log.d(TAG, "Trying provider $type (attempt $attempt)")
                val providerStart = System.currentTimeMillis()
                val response = requestFn(cfg)
                val latency  = System.currentTimeMillis() - providerStart
                providerManager.reportSuccess(type, latency)
                return ChainResult(
                    response       = response,
                    usedProvider   = type,
                    attemptCount   = attempt + 1,
                    totalLatencyMs = System.currentTimeMillis() - start
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Provider $type failed: ${e.message}")
                providerManager.reportError(type, e)
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("All providers in fallback chain failed")
    }
}
