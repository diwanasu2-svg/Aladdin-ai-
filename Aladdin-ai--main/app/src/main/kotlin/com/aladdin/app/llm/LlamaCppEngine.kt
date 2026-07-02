package com.aladdin.app.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LlamaCppEngine — Items 18, 86: llama.cpp JNI Integration
 *
 * Wraps the llama.cpp native library via JNI.
 * Falls back gracefully when the .so is absent.
 * Supports: model loading, inference, streaming tokens, quantization levels.
 */
@Singleton
class LlamaCppEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val quantSelector: QuantizedModelSelector
) {
    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val LIB = "llama"
        private var nativeLoaded = false

        init {
            nativeLoaded = try {
                System.loadLibrary(LIB)
                Log.i(TAG, "llama.cpp native library loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "llama.cpp .so not found — engine unavailable. Place libllama.so in jniLibs/<abi>/")
                false
            }
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nGpuLayers: Int): Long
    private external fun nativeCreateContext(modelHandle: Long, prompt: String): Long
    private external fun nativePredict(ctxHandle: Long, maxTokens: Int, temperature: Float): String
    private external fun nativePredictStreaming(ctxHandle: Long, maxTokens: Int, temperature: Float, callback: TokenCallback): Unit
    private external fun nativeFreeContext(ctxHandle: Long)
    private external fun nativeFreeModel(modelHandle: Long)
    private external fun nativeIsGpuSupported(): Boolean

    // ── Token callback interface ──────────────────────────────────────────────

    interface TokenCallback {
        fun onToken(token: String): Boolean  // return false to stop
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var modelHandle: Long = 0L
    private var loadedModelPath: String? = null
    private val isLoaded: Boolean get() = modelHandle != 0L
    val isAvailable: Boolean get() = nativeLoaded

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Model management ──────────────────────────────────────────────────────

    suspend fun loadModel(baseModelName: String = "llama-3.2-3b-instruct"): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLoaded) { Log.w(TAG, "Native library not loaded"); return@withContext false }

        val selection = quantSelector.selectQuantization(baseModelName)
        val modelDir  = File(context.filesDir, "models/llama")
        val modelFile = File(modelDir, selection.fileSuffix)

        if (!modelFile.exists()) {
            Log.e(TAG, "Model not found: ${modelFile.absolutePath}")
            return@withContext false
        }

        try {
            val gpuLayers = if (nativeIsGpuSupported()) 32 else 0
            modelHandle = nativeLoadModel(modelFile.absolutePath, nCtx = 4096, nGpuLayers = gpuLayers)
            loadedModelPath = modelFile.absolutePath
            Log.i(TAG, "Model loaded: ${selection.fileSuffix} (GPU layers=$gpuLayers)")
            modelHandle != 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            false
        }
    }

    fun unloadModel() {
        if (isLoaded) {
            try { nativeFreeModel(modelHandle) } catch (_: Exception) {}
            modelHandle = 0L
            loadedModelPath = null
            Log.d(TAG, "Model unloaded")
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.Default) {
        if (!isLoaded) throw IllegalStateException("Model not loaded — call loadModel() first")
        try {
            val ctxHandle = nativeCreateContext(modelHandle, prompt)
            val response  = nativePredict(ctxHandle, maxTokens, temperature)
            nativeFreeContext(ctxHandle)
            response
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            throw e
        }
    }

    fun generateStreaming(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f
    ): Flow<String> = flow {
        if (!isLoaded) throw IllegalStateException("Model not loaded")
        val ctxHandle = nativeCreateContext(modelHandle, prompt)
        try {
            val channel = kotlinx.coroutines.channels.Channel<String>(64)
            scope.launch {
                nativePredictStreaming(ctxHandle, maxTokens, temperature, object : TokenCallback {
                    override fun onToken(token: String): Boolean {
                        channel.trySend(token)
                        return !channel.isClosedForSend
                    }
                })
                channel.close()
            }
            for (token in channel) emit(token)
        } finally {
            try { nativeFreeContext(ctxHandle) } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.Default)

    // ── Retry logic ───────────────────────────────────────────────────────────

    suspend fun generateWithRetry(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        maxRetries: Int = 3
    ): String {
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < maxRetries) {
            try { return generate(prompt, maxTokens, temperature) }
            catch (e: Exception) {
                lastErr = e
                attempt++
                Log.w(TAG, "Generation attempt $attempt failed: ${e.message}")
                delay(1000L * attempt)
            }
        }
        throw lastErr ?: RuntimeException("All retry attempts failed")
    }
}
