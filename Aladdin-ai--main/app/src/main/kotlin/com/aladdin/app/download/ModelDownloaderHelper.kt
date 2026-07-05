package com.aladdin.app.download

import android.content.Context
import android.util.Log
import com.aladdin.voicecore.models.DownloadProgress
import com.aladdin.voicecore.models.ModelDownloader
import com.aladdin.voicecore.models.ModelSpec
import com.aladdin.voicecore.models.ArchiveType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ModelDownloaderHelper — app-layer wrapper around voice-core's [ModelDownloader].
 *
 * Adds:
 *   - MiniLM embedding model download spec
 *   - Wake word model download spec
 *   - Progress callbacks for UI integration
 *   - Idempotent: skips already-downloaded models
 */
@Singleton
class ModelDownloaderHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloaderHelper"

        val ALL_MODELS = ModelDownloader.DEFAULT_MODELS + listOf(
            // Item 9c: the old "litert-community/all-MiniLM-L6-v2" repo/file no longer
            // resolves (HF now returns 401 for it — repo is gone/renamed), so this
            // points at a verified public mirror of the same quantized TFLite model.
            ModelSpec(
                id = "minilm-tflite",
                name = "MiniLM Embedding Model",
                url = "https://huggingface.co/Nihal2000/all-MiniLM-L6-v2-quant.tflite/resolve/main/all-MiniLM-L6-v2-quant.tflite",
                destDir = "models/minilm",
                archiveType = ArchiveType.RAW,
                destFileName = "minilm-l6-v2.tflite",
                sizeMb = 22,
                sha256 = "0aac5b0b76be23ab94f065a7fab6e0daead5e57f6ff7d55e19a2641d6a81f276"
            ),
            // Item 9c: the old "rhasspy/wyoming-openwakeword" v1.0.0 release tag/asset
            // no longer exists (404). Switched to the upstream openWakeWord project's
            // own GitHub release, which still hosts the real hey_jarvis tflite model.
            ModelSpec(
                id = "wakeword-tflite",
                name = "Wake Word Model",
                url = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.tflite",
                destDir = "models/wakeword",
                archiveType = ArchiveType.RAW,
                destFileName = "aladdin_wakeword.tflite",
                sizeMb = 2,
                sha256 = "14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2"
            )
        )
    }

    private val downloader = ModelDownloader(context)
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget convenience entry point for app startup (e.g. Application.onCreate()).
     * Launches [downloadModels] on an internal background scope and just logs progress,
     * since callers in non-suspend contexts can't await it directly.
     */
    fun ensureModelsPresent() {
        helperScope.launch {
            downloadModels(
                onProgress = { name, percent -> Log.d(TAG, "ensureModelsPresent: $name $percent%") },
                onComplete = { Log.i(TAG, "ensureModelsPresent: all required models ready") },
                onError = { err -> Log.w(TAG, "ensureModelsPresent: $err") }
            )
        }
    }

    fun areAllModelsReady(): Boolean =
        ALL_MODELS.all { ModelDownloader.isModelReady(context, it) }

    fun getPendingModels(): List<ModelSpec> =
        ALL_MODELS.filter { !ModelDownloader.isModelReady(context, it) }

    suspend fun downloadModels(
        onProgress: (modelName: String, percent: Int) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val pending = getPendingModels()
        if (pending.isEmpty()) {
            Log.i(TAG, "All models already present — skipping download")
            onComplete()
            return
        }

        Log.i(TAG, "Downloading ${pending.size} models: ${pending.map { it.name }}")

        downloader.downloadAll(pending)
            .catch { e ->
                Log.e(TAG, "Download flow error: ${e.message}", e)
                onError(e.message ?: "Download failed")
            }
            .collect { progress ->
                when (progress) {
                    is DownloadProgress.Started ->
                        Log.i(TAG, "Starting download of ${progress.total} models")

                    is DownloadProgress.Downloading -> {
                        Log.d(TAG, "Downloading ${progress.name}: ${progress.percent}% (${progress.index}/${progress.total})")
                        onProgress(progress.name, progress.percent)
                    }

                    is DownloadProgress.Extracting ->
                        onProgress("Extracting ${progress.name}", 99)

                    is DownloadProgress.Error -> {
                        Log.e(TAG, "Failed to download ${progress.modelId}: ${progress.message}")
                        onError("${progress.modelId}: ${progress.message}")
                    }

                    is DownloadProgress.Done -> {
                        Log.i(TAG, "All models downloaded successfully")
                        onComplete()
                    }
                }
            }
    }

    /**
     * Download a single model by spec ID.
     */
    suspend fun downloadSingle(
        modelId: String,
        onProgress: (Int) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val spec = ALL_MODELS.find { it.id == modelId }
            ?: run { onError("Unknown model ID: $modelId"); return }

        if (ModelDownloader.isModelReady(context, spec)) {
            onComplete()
            return
        }

        downloader.downloadSingle(spec)
            .catch { e -> onError(e.message ?: "Download failed") }
            .collect { progress ->
                when (progress) {
                    is DownloadProgress.Downloading -> onProgress(progress.percent)
                    is DownloadProgress.Done -> onComplete()
                    is DownloadProgress.Error -> onError(progress.message)
                    else -> Unit
                }
            }
    }
}


// ──────────────────────────────────────────────────────────────────
// Items 8-12: Extended model specifications and download management
// ──────────────────────────────────────────────────────────────────

/*
 * Item 8:  Whisper STT (small.en 488 MB) — SHA-256 verified download
 * Item 9:  MiniLM TFLite + vocab for on-device embeddings (22 MB)
 * Item 10: Llama 3.2 3B Q4_K_M GGUF for on-device LLM (1.9 GB)
 * Item 11: Wake-word TFLite neural model (3 MB)
 * Item 12: Piper ONNX voice model (63 MB) + config JSON
 */

object ExtendedModelSpecs {
    // Item 8: Whisper STT
    val WHISPER_SMALL_EN = ModelInfo(
        id = "whisper-small-en",
        displayName = "Whisper STT (small.en)",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin",
        destPath = "models/whisper/ggml-small.en.bin",
        sizeMb = 488,
        sha256 = "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d",
        required = true
    )
    // Item 9: MiniLM embeddings
    // Item 9c: the old "litert-community/all-MiniLM-L6-v2" repo/file no longer
    // resolves (HF now returns 401 for it — repo is gone/renamed), so this
    // points at a verified public mirror of the same quantized TFLite model.
    val MINILM_TFLITE = ModelInfo(
        id = "minilm-tflite",
        displayName = "MiniLM Embedding Model",
        url = "https://huggingface.co/Nihal2000/all-MiniLM-L6-v2-quant.tflite/resolve/main/all-MiniLM-L6-v2-quant.tflite",
        destPath = "models/minilm/minilm-l6-v2.tflite",
        sizeMb = 22,
        sha256 = "0aac5b0b76be23ab94f065a7fab6e0daead5e57f6ff7d55e19a2641d6a81f276"
    )
    val MINILM_VOCAB = ModelInfo(
        id = "minilm-vocab",
        displayName = "MiniLM Vocabulary",
        url = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt",
        destPath = "models/minilm/vocab.txt",
        sizeMb = 1
    )
    // Item 10: Llama 3.2 3B Q4_K_M
    val LLAMA_3B_Q4 = ModelInfo(
        id = "llama-3b-q4",
        displayName = "Llama 3.2 3B (Q4_K_M, on-device LLM)",
        url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        destPath = "models/llama/llama-3.2-3b-instruct.Q4_K_M.gguf",
        sizeMb = 1900
    )
    // Item 11: Wake-word neural model
    // Item 9c: the old "rhasspy/wyoming-openwakeword" v1.0.0 release tag/asset
    // no longer exists (404). Switched to the upstream openWakeWord project's
    // own GitHub release, which still hosts the real hey_jarvis tflite model.
    val WAKEWORD_TFLITE = ModelInfo(
        id = "wakeword-tflite",
        displayName = "Wake Word Neural Model",
        url = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.tflite",
        destPath = "models/wakeword/aladdin_wakeword.tflite",
        sizeMb = 2,
        sha256 = "14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2",
        required = true
    )
    // Item 12: Piper TTS voice model
    val PIPER_LESSAC = ModelInfo(
        id = "piper-lessac-onnx",
        displayName = "Piper TTS Voice (en_US lessac)",
        url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
        destPath = "models/piper/en_US-lessac-medium.onnx",
        sizeMb = 63
    )
    val PIPER_LESSAC_CONFIG = ModelInfo(
        id = "piper-lessac-config",
        displayName = "Piper TTS Config",
        url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
        destPath = "models/piper/en_US-lessac-medium.onnx.json",
        sizeMb = 1
    )

    val ALL_MODELS = listOf(WAKEWORD_TFLITE, WHISPER_SMALL_EN, MINILM_TFLITE, MINILM_VOCAB, PIPER_LESSAC, PIPER_LESSAC_CONFIG, LLAMA_3B_Q4)
    val REQUIRED_MODELS = ALL_MODELS.filter { it.required }
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val url: String,
    val destPath: String,
    val sizeMb: Int = 0,
    val sha256: String? = null,
    val required: Boolean = false
)
