package com.aladdin.app.llm

import android.util.Log
import com.aladdin.app.network.NetworkMonitor
import com.aladdin.app.offline.OfflineFallback
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLMBackendManager — Items 43-47.
 * Item 43: llama.cpp primary on-device backend.
 * Item 44: MLC LLM GPU-accelerated alternative.
 * Item 45: GPU via delegate selection (GPU → CPU fallback).
 * Item 46: Quantized models — auto-selects Q4/Q5/Q8 based on device RAM.
 * Item 47: Offline inference — seamless cloud ↔ local switching.
 *
 * Priority: Cloud LLM → llama.cpp GPU → llama.cpp CPU → MLC LLM → OfflineFallback
 */
@Singleton
class LLMBackendManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaEngine: LlamaCppEngine,
    private val mlcEngine: MlcLlmEngine,
    private val quantSelector: QuantizedModelSelector,
    private val offlineFallback: OfflineFallback,
    private val networkMonitor: NetworkMonitor
) {
    companion object { private const val TAG = "LLMBackendManager" }

    enum class Backend { CLOUD, LLAMA_CPP_GPU, LLAMA_CPP_CPU, MLC_LLM, OFFLINE_FALLBACK }

    private var activeBackend = Backend.CLOUD
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Item 43/44/45: Initialize local backends. GPU first, CPU fallback. */
    suspend fun initialize() {
        Log.i(TAG, "Initializing LLM backends…")
        val llamaReady = withContext(Dispatchers.IO) { llamaEngine.loadModel() }
        Log.i(TAG, "llama.cpp: $llamaReady")
        val mlcReady = withContext(Dispatchers.IO) { mlcEngine.initialize(preferGpu = true) }  // Item 45
        Log.i(TAG, "MLC LLM: $mlcReady GPU=${if (mlcReady) mlcEngine.getGpuInfo() else "N/A"}")
        monitorNetwork()  // Item 47
    }

    /** Item 47: Watch network changes and switch backends automatically. */
    private fun monitorNetwork() = scope.launch {
        networkMonitor.state.map { it.isConnected }.collect { online ->
            val prev = activeBackend
            activeBackend = if (!online) selectOfflineBackend() else Backend.CLOUD
            if (prev != activeBackend) Log.i(TAG, "Backend: $prev → $activeBackend (online=$online)")
        }
    }

    private fun selectOfflineBackend() = when {
        llamaEngine.isAvailable && llamaEngine.isLoaded -> Backend.LLAMA_CPP_GPU
        mlcEngine.isAvailable && mlcEngine.isLoaded -> Backend.MLC_LLM
        else -> Backend.OFFLINE_FALLBACK
    }

    /** Item 46: Auto-select quantization based on device RAM. */
    fun getRecommendedQuantization(model: String): String {
        val sel = quantSelector.selectQuantization(model)
        Log.i(TAG, "Quant for $model: ${sel.quantLevel.label} (${sel.reason})")
        return sel.quantLevel.label
    }

    /** Item 47: Generate with automatic cloud/local fallback. */
    suspend fun generate(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): BackendResponse {
        val backend = if (networkMonitor.isConnected) Backend.CLOUD else selectOfflineBackend()
        return when (backend) {
            Backend.CLOUD -> BackendResponse("", Backend.CLOUD, useCloud = true)
            Backend.LLAMA_CPP_GPU, Backend.LLAMA_CPP_CPU -> try {
                BackendResponse(llamaEngine.generateWithRetry(prompt, maxTokens, temperature), backend)
            } catch (e: Exception) { Log.e(TAG, "llama.cpp failed: ${e.message}"); fallback(prompt) }
            Backend.MLC_LLM -> try {
                BackendResponse(mlcEngine.generate(prompt, maxTokens), Backend.MLC_LLM)
            } catch (e: Exception) { Log.e(TAG, "MLC LLM failed: ${e.message}"); fallback(prompt) }
            Backend.OFFLINE_FALLBACK -> fallback(prompt)
        }
    }

    fun generateStreaming(prompt: String, maxTokens: Int = 512): Flow<String> =
        if (llamaEngine.isAvailable && llamaEngine.isLoaded) llamaEngine.generateStreaming(prompt, maxTokens)
        else flow { val r = generate(prompt, maxTokens); if (r.text.isNotBlank()) emit(r.text) }

    private suspend fun fallback(prompt: String) = offlineFallback.getResponse(prompt).let { BackendResponse(it.text, Backend.OFFLINE_FALLBACK) }

    fun shutdown() { scope.cancel(); mlcEngine.shutdown() }
    fun getActiveBackend() = activeBackend
}

data class BackendResponse(val text: String, val backend: LLMBackendManager.Backend, val useCloud: Boolean = false)