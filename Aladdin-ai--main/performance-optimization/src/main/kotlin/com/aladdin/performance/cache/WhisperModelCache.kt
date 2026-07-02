package com.aladdin.performance.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two-tier cache for Whisper GGUF model files:
 *  L1 = RAM reference (ByteArray)  — instant access, limited by RAM budget
 *  L2 = Disk cache                 — survives process restart, up to 500 MB
 *
 * Supports lazy loading: the model is not loaded until first access.
 */
class WhisperModelCache(context: Context) {

    companion object {
        private const val TAG = "WhisperModelCache"
        const val KEY_TINY   = "whisper-tiny"
        const val KEY_BASE   = "whisper-base"
        const val KEY_SMALL  = "whisper-small"
        const val KEY_MEDIUM = "whisper-medium"
    }

    private val ram  = LruModelCache<ByteArray>(maxBytes = 80 * 1024 * 1024L)  // 80 MB
    private val disk = DiskModelCache(context, "whisper_cache", 500 * 1024 * 1024L)

    var state: CacheState = CacheState.NOT_LOADED
        private set

    /**
     * Load a Whisper GGUF model lazily. Checks RAM → disk → asset in that order.
     */
    suspend fun loadModel(key: String, assetPath: String, context: Context): ByteArray? =
        withContext(Dispatchers.IO) {
            // L1: RAM
            ram.get(key)?.also {
                Log.d(TAG, "RAM hit: $key (${it.size / 1024}KB)")
                return@withContext it
            }

            // L2: Disk
            disk.get(key)?.also { bytes ->
                Log.d(TAG, "Disk hit: $key — warming RAM")
                ram.put(key, bytes, bytes.size.toLong())
                state = CacheState.LOADED
                return@withContext bytes
            }

            // L3: Load from assets / filesystem
            state = CacheState.LOADING
            val bytes = runCatching {
                context.assets.open(assetPath).readBytes()
            }.getOrElse {
                File(assetPath).takeIf { f -> f.exists() }?.readBytes()
            }

            bytes?.let {
                disk.put(key, it)
                ram.put(key, it, it.size.toLong())
                state = CacheState.LOADED
                Log.i(TAG, "Loaded $key from asset (${it.size / 1024 / 1024}MB)")
            } ?: run {
                state = CacheState.ERROR
                Log.e(TAG, "Failed to load $key from $assetPath")
            }
            bytes
        }

    fun evict(key: String) { ram.evict(key); disk.evict(key); state = CacheState.EVICTED }
    fun evictAll() { ram.evictAll(); disk.evictAll(); state = CacheState.NOT_LOADED }
    fun isLoaded(key: String) = ram.containsKey(key) || disk.contains(key)
    fun ramUsageBytes() = ram.currentSizeBytes()
    fun diskUsageBytes() = disk.diskUsageBytes()
}
