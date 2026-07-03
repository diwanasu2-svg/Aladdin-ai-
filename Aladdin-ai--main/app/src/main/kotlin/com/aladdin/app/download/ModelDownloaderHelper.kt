package com.aladdin.app.download

import android.content.Context
import android.util.Log
import com.aladdin.voicecore.models.DownloadProgress
import com.aladdin.voicecore.models.ModelDownloader
import com.aladdin.voicecore.models.ModelSpec
import com.aladdin.voicecore.models.ArchiveType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
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
            ModelSpec(
                id = "minilm-tflite",
                name = "MiniLM Embedding Model",
                url = "https://huggingface.co/litert-community/all-MiniLM-L6-v2/resolve/main/all-MiniLM-L6-v2.tflite",
                destDir = "models/minilm",
                archiveType = ArchiveType.RAW,
                destFileName = "minilm-l6-v2.tflite",
                sizeMb = 22
            ),
            ModelSpec(
                id = "wakeword-tflite",
                name = "Wake Word Model",
                url = "https://github.com/rhasspy/wyoming-openwakeword/releases/download/v1.0.0/hey_jarvis.tflite",
                destDir = "models/wakeword",
                archiveType = ArchiveType.RAW,
                destFileName = "aladdin_wakeword.tflite",
                sizeMb = 3
            )
        )
    }

    private val downloader = ModelDownloader(context)

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
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        required = true
    )
    // Item 9: MiniLM embeddings
    val MINILM_TFLITE = ModelInfo(
        id = "minilm-tflite",
        displayName = "MiniLM Embedding Model",
        url = "https://huggingface.co/litert-community/all-MiniLM-L6-v2/resolve/main/all-MiniLM-L6-v2.tflite",
        destPath = "models/minilm/minilm-l6-v2.tflite",
        sizeMb = 22
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
    val WAKEWORD_TFLITE = ModelInfo(
        id = "wakeword-tflite",
        displayName = "Wake Word Neural Model",
        url = "https://github.com/rhasspy/wyoming-openwakeword/releases/download/v1.0.0/hey_jarvis.tflite",
        destPath = "models/wakeword/aladdin_wakeword.tflite",
        sizeMb = 3,
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
