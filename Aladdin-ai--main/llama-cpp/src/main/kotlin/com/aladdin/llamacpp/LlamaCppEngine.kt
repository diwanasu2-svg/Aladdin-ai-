package com.aladdin.llamacpp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin façade over the llama.cpp JNI bridge (llama_bridge.cpp / libllama_bridge.so).
 *
 * Runs a local GGUF model (e.g. gemma-3-1b-it.Q4_K_M.gguf) entirely on-device:
 *  - No Ollama server
 *  - No internet connection at inference time
 *  - Model file is downloaded once (see ModelDownloader in :voice-core) and then
 *    loaded straight from local storage on every subsequent app launch.
 */
class LlamaCppEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlamaCppEngine"
        @Volatile private var libraryLoaded = false

        private fun ensureLibraryLoaded(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("llama_bridge")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libllama_bridge.so not found (build the :llama-cpp module's " +
                        "native target first): ${e.message}")
                false
            }
        }
    }

    private var handle: Long = 0L
    @Volatile var isReady: Boolean = false
        private set

    /** Directory where the GGUF model is stored after ModelDownloader fetches it once. */
    fun defaultModelPath(fileName: String = "gemma-3-1b-it.Q4_K_M.gguf"): String =
        File(context.filesDir, "models/llama/$fileName").absolutePath

    /**
     * Loads the GGUF model into memory. Call once, off the main thread.
     * @param modelPath absolute path to a local .gguf file (never a URL — fully offline).
     */
    suspend fun init(
        modelPath: String = defaultModelPath(),
        contextSize: Int = 2048,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        gpuLayers: Int = 0
    ): Boolean = withContext(Dispatchers.Default) {
        if (!ensureLibraryLoaded()) return@withContext false
        if (!File(modelPath).exists()) {
            Log.e(TAG, "GGUF model not found at $modelPath — run ModelDownloader first")
            return@withContext false
        }
        handle = nativeInit(modelPath, contextSize, threads, gpuLayers)
        isReady = handle != 0L
        if (isReady) Log.i(TAG, "llama.cpp ready: $modelPath") else Log.e(TAG, "llama.cpp init failed")
        isReady
    }

    /** Blocking (suspend) single-shot completion — simplest path for short replies. */
    suspend fun complete(prompt: String, maxTokens: Int = 256): String = withContext(Dispatchers.Default) {
        if (!isReady) return@withContext ""
        nativeComplete(handle, prompt, maxTokens)
    }

    /**
     * Streaming completion — emits one chunk (token/piece) at a time so the caller
     * (see AIEngine → TTSEngine.enqueueSentence) can start speaking the first
     * sentence before generation finishes, matching the low-latency voice pipeline.
     */
    fun completeStreaming(prompt: String, maxTokens: Int = 256): Flow<String> = callbackFlow {
        if (!isReady) { close(); return@callbackFlow }
        val callback = object : TokenCallback {
            override fun onToken(token: String): Boolean {
                val sendResult = trySend(token)
                return sendResult.isSuccess && !isClosedForSend
            }
        }
        withContext(Dispatchers.Default) {
            nativeCompleteStreaming(handle, prompt, maxTokens, callback)
        }
        close()
        awaitClose { }
    }

    fun shutdown() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
        isReady = false
    }

    /** Called from native code (JNI) once per generated token. Return false to stop early. */
    interface TokenCallback {
        fun onToken(token: String): Boolean
    }

    // ─── JNI ──────────────────────────────────────────────────────────────────
    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    private external fun nativeComplete(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeCompleteStreaming(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback)
    private external fun nativeFree(handle: Long)
}
