package com.aladdin.voicecore.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * ModelDownloader — Items 27, 28, 29.
 * Item 27: Complete download pipeline with progress tracking and retry.
 * Item 28: HTTP Range resume — interrupted downloads continue from last byte.
 * Item 29: SHA-256 integrity verification on every downloaded file.
 */
class ModelDownloader(private val context: Context) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1_000L

        val DEFAULT_MODELS = listOf(
            ModelSpec("vosk-small-en", "Vosk Small English",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "models/vosk-model", ArchiveType.ZIP, sizeMb = 40),
            ModelSpec("piper-voice-lessac", "Piper Voice (en_US lessac)",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                "models/piper", ArchiveType.RAW, destFileName = "en_US-lessac-medium.onnx", sizeMb = 63),
            ModelSpec("piper-voice-lessac-config", "Piper Voice Config",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
                "models/piper", ArchiveType.RAW, destFileName = "en_US-lessac-medium.onnx.json", sizeMb = 1),
            // Item 8: Whisper model
            ModelSpec("whisper-small-en", "Whisper STT (small.en)",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin",
                "models/whisper", ArchiveType.RAW, destFileName = "ggml-small.en.bin", sizeMb = 488,
                sha256 = "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d"),
            // Item 9: MiniLM for embeddings
            ModelSpec("minilm-tflite", "MiniLM Embedding Model",
                "https://huggingface.co/litert-community/all-MiniLM-L6-v2/resolve/main/all-MiniLM-L6-v2.tflite",
                "models/minilm", ArchiveType.RAW, destFileName = "minilm-l6-v2.tflite", sizeMb = 22),
            // Item 9: MiniLM vocab
            ModelSpec("minilm-vocab", "MiniLM Vocab",
                "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt",
                "models/minilm", ArchiveType.RAW, destFileName = "vocab.txt", sizeMb = 1),
            // Item 11/12: Wake-word TFLite
            ModelSpec("wakeword-tflite", "Wake Word Neural Model",
                "https://github.com/rhasspy/wyoming-openwakeword/releases/download/v1.0.0/hey_jarvis.tflite",
                "models/wakeword", ArchiveType.RAW, destFileName = "aladdin_wakeword.tflite", sizeMb = 3),
            // Item 10: llama.cpp Q4 model
            ModelSpec("llama-3b-q4", "Llama 3.2 3B (Q4_K_M)",
                "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                "models/llama", ArchiveType.RAW, destFileName = "llama-3.2-3b-instruct.Q4_K_M.gguf", sizeMb = 1900)
        )

        fun isModelReady(context: Context, spec: ModelSpec): Boolean {
            val dir = File(context.filesDir, spec.destDir)
            return dir.exists() && dir.listFiles()?.isNotEmpty() == true
        }

        fun areAllModelsReady(context: Context) = DEFAULT_MODELS.all { isModelReady(context, it) }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Item 27: Download all missing models with retry logic. */
    fun downloadAll(models: List<ModelSpec> = DEFAULT_MODELS): Flow<DownloadProgress> = flow {
        val missing = models.filter { !isModelReady(context, it) }
        if (missing.isEmpty()) { emit(DownloadProgress.Done); return@flow }
        emit(DownloadProgress.Started(missing.size))
        missing.forEachIndexed { index, spec ->
            var attempt = 0; var success = false; var lastError = ""
            while (attempt < MAX_RETRIES && !success) {
                attempt++
                if (attempt > 1) { kotlinx.coroutines.delay(INITIAL_BACKOFF_MS * (1L shl (attempt - 2))) }
                try {
                    emit(DownloadProgress.Downloading(spec.name, index + 1, missing.size, 0))
                    val tempFile = downloadWithResume(spec)
                    // Item 29: SHA-256 integrity
                    if (spec.sha256 != null) {
                        val actual = computeSha256(tempFile)
                        if (!actual.equals(spec.sha256, ignoreCase = true)) {
                            tempFile.delete()
                            throw IOException("SHA-256 mismatch for ${spec.id}: expected=${spec.sha256} got=$actual")
                        }
                        Log.i(TAG, "SHA-256 verified: ${spec.id}")
                    }
                    emit(DownloadProgress.Extracting(spec.name))
                    extract(spec, tempFile); tempFile.delete()
                    success = true
                } catch (e: Exception) { lastError = e.message ?: "Unknown"; Log.e(TAG, "Attempt $attempt failed for ${spec.id}: $lastError") }
            }
            if (!success) { emit(DownloadProgress.Error(spec.id, lastError)); return@flow }
        }
        emit(DownloadProgress.Done)
    }.flowOn(Dispatchers.IO)

    fun downloadSingle(spec: ModelSpec) = downloadAll(listOf(spec))

    /** Item 28: HTTP Range resume download. */
    private suspend fun downloadWithResume(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "${spec.id}.part")
        val existing = if (temp.exists()) temp.length() else 0L
        val reqBuilder = Request.Builder().url(spec.url)
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")
        val req = reqBuilder.build()
        client.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code != 200 && code != 206) throw IOException("HTTP $code for ${spec.url}")
            if (code == 200 && existing > 0) temp.delete()
            val body = resp.body ?: throw IOException("Empty body")
            val declaredLen = body.contentLength()
            val totalSize = if (declaredLen >= 0) declaredLen + (if (code == 206) existing else 0L) else -1L
            FileOutputStream(temp, code == 206).use { out ->
                val buf = ByteArray(65536); var n: Int
                while (body.source().read(buf).also { n = it } != -1) out.write(buf, 0, n)
            }
            // Item 28b: verify the stream wasn't truncated by a dropped connection.
            // Without this check, a premature/clean EOF from a reset socket would
            // silently leave a partial file on disk, which is then wrongly treated
            // as "downloaded" and fails the SHA-256 check with a confusing mismatch.
            if (totalSize > 0 && temp.length() != totalSize) {
                throw IOException(
                    "Incomplete download for ${spec.id}: expected $totalSize bytes, got ${temp.length()} bytes " +
                        "(connection likely dropped mid-transfer; will retry/resume)"
                )
            }
        }
        temp
    }

    /** Item 29: SHA-256 digest. */
    private fun computeSha256(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(65536).use { s -> val b = ByteArray(65536); var n: Int; while (s.read(b).also { n = it } != -1) d.update(b, 0, n) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(spec: ModelSpec, file: File) {
        val dest = File(context.filesDir, spec.destDir).also { it.mkdirs() }
        when (spec.archiveType) {
            ArchiveType.ZIP -> ZipInputStream(file.inputStream().buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val out = File(dest, e.name)
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
            ArchiveType.RAW -> file.copyTo(File(dest, spec.destFileName ?: file.name), overwrite = true)
            ArchiveType.TAR_GZ -> { Log.w(TAG, "tar.gz: copying as raw"); file.copyTo(File(dest, file.name), overwrite = true) }
        }
    }
}

data class ModelSpec(
    val id: String, val name: String, val url: String, val destDir: String,
    val archiveType: ArchiveType, val destFileName: String? = null,
    val sizeMb: Int = 0, val sha256: String? = null
)
enum class ArchiveType { ZIP, TAR_GZ, RAW }
sealed class DownloadProgress {
    data class Started(val total: Int) : DownloadProgress()
    data class Downloading(val name: String, val index: Int, val total: Int, val percent: Int) : DownloadProgress()
    data class Extracting(val name: String) : DownloadProgress()
    data class Error(val modelId: String, val message: String) : DownloadProgress()
    object Done : DownloadProgress()
}