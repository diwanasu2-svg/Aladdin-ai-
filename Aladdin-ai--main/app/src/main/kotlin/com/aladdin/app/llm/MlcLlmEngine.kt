package com.aladdin.app.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MlcLlmEngine — Items 17, 87: MLC LLM Android Runtime with GPU acceleration.
 *
 * Uses MLC-Chat Android library for on-device GPU inference.
 * Falls back to CPU when GPU is unavailable or fails.
 */
@Singleton
class MlcLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MlcLlmEngine"
        private const val LIB = "mlc_llm"
        private var nativeLoaded = false

        init {
            nativeLoaded = try {
                System.loadLibrary(LIB)
                Log.i(TAG, "MLC LLM native library loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "MLC LLM .so not found — GPU inference unavailable. Fallback to CPU via llama.cpp")
                false
            }
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeCreateEngine(modelDir: String, modelLib: String, useGpu: Boolean): Long
    private external fun nativeChat(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetGpuInfo(): String

    // ── State ─────────────────────────────────────────────────────────────────

    private var engineHandle: Long = 0L
    val isAvailable: Boolean get() = nativeLoaded
    val isLoaded: Boolean get() = engineHandle != 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun initialize(
        modelId: String = "Llama-3.2-1B-Instruct-q4f16_1",
        preferGpu: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext false
        if (isLoaded) return@withContext true

        val modelDir = "${context.filesDir.absolutePath}/models/mlc/$modelId"
        val modelLib = "$modelDir/$modelId-android.so"

        return@withContext try {
            val useGpu = preferGpu && isGpuAvailable()
            Log.i(TAG, "Initializing MLC LLM (GPU=$useGpu, model=$modelId)")
            engineHandle = nativeCreateEngine(modelDir, modelLib, useGpu)
            if (engineHandle == 0L && useGpu) {
                Log.w(TAG, "GPU init failed — retrying on CPU")
                engineHandle = nativeCreateEngine(modelDir, modelLib, false)
            }
            val ok = engineHandle != 0L
            Log.i(TAG, "MLC LLM initialized: $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "MLC LLM init failed: ${e.message}", e)
            false
        }
    }

    fun shutdown() {
        if (isLoaded) {
            try { nativeDestroy(engineHandle) } catch (_: Exception) {}
            engineHandle = 0L
            Log.d(TAG, "MLC LLM engine shut down")
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    suspend fun generate(prompt: String, maxTokens: Int = 512): String = withContext(Dispatchers.Default) {
        if (!isLoaded) throw IllegalStateException("MLC LLM not initialized — call initialize() first")
        try {
            nativeChat(engineHandle, prompt, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "MLC inference failed: ${e.message}", e)
            throw e
        }
    }

    fun resetConversation() {
        if (isLoaded) {
            try { nativeReset(engineHandle) } catch (_: Exception) {}
        }
    }

    fun getGpuInfo(): String {
        return if (nativeLoaded) {
            try { nativeGetGpuInfo() } catch (_: Exception) { "GPU info unavailable" }
        } else "Native library not loaded"
    }

    private fun isGpuAvailable(): Boolean {
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature("android.hardware.vulkan.level") ||
            pm.hasSystemFeature("android.hardware.opengles.aep")
        } catch (_: Exception) { false }
    }
}
