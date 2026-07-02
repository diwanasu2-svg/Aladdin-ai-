package com.aladdin.performance.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Disk-based model cache. Stores raw byte arrays keyed by a content hash.
 * Used for persistence across process restarts (Whisper GGUF, Piper ONNX, embeddings).
 */
class DiskModelCache(
    context: Context,
    private val subDir: String = "model_cache",
    private val maxDiskBytes: Long = 500 * 1024 * 1024L  // 500 MB
) {
    companion object { private const val TAG = "DiskModelCache" }

    private val cacheDir = File(context.filesDir, subDir).also { it.mkdirs() }

    suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = fileFor(key)
        if (f.exists()) {
            Log.d(TAG, "Disk hit: $key (${f.length() / 1024}KB)")
            f.readBytes()
        } else null
    }

    suspend fun put(key: String, data: ByteArray) = withContext(Dispatchers.IO) {
        pruneIfNeeded(data.size.toLong())
        val f = fileFor(key)
        f.writeBytes(data)
        Log.i(TAG, "Disk stored: $key (${data.size / 1024}KB)")
    }

    suspend fun putFile(key: String, source: File) = withContext(Dispatchers.IO) {
        pruneIfNeeded(source.length())
        source.copyTo(fileFor(key), overwrite = true)
        Log.i(TAG, "Disk stored file: $key (${source.length() / 1024}KB)")
    }

    fun getFile(key: String): File? = fileFor(key).takeIf { it.exists() }

    fun contains(key: String) = fileFor(key).exists()

    fun evict(key: String) { fileFor(key).delete() }

    fun evictAll() { cacheDir.listFiles()?.forEach { it.delete() } }

    fun diskUsageBytes() = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(key: String): File {
        val safe = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$safe.bin")
    }

    private fun pruneIfNeeded(incoming: Long) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var used = files.sumOf { it.length() }
        while (used + incoming > maxDiskBytes && files.isNotEmpty()) {
            val f = files.removeFirst()
            Log.d(TAG, "Disk prune: ${f.name} (${f.length() / 1024}KB)")
            used -= f.length()
            f.delete()
        }
    }
}
