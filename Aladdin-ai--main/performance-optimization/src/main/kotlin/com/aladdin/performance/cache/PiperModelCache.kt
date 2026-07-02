package com.aladdin.performance.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two-tier cache for Piper TTS ONNX models + JSON config files.
 * RAM budget: 40 MB  |  Disk budget: 200 MB
 */
class PiperModelCache(context: Context) {

    companion object {
        private const val TAG = "PiperModelCache"
        const val KEY_ONNX   = "piper-onnx"
        const val KEY_CONFIG = "piper-config"
    }

    private val ram  = LruModelCache<ByteArray>(maxBytes = 40 * 1024 * 1024L)
    private val disk = DiskModelCache(context, "piper_cache", 200 * 1024 * 1024L)

    var state: CacheState = CacheState.NOT_LOADED
        private set

    suspend fun loadOnnx(assetPath: String, context: Context): ByteArray? =
        loadFromTiers(KEY_ONNX, assetPath, context)

    suspend fun loadConfig(assetPath: String, context: Context): ByteArray? =
        loadFromTiers(KEY_CONFIG, assetPath, context)

    private suspend fun loadFromTiers(key: String, assetPath: String, context: Context): ByteArray? =
        withContext(Dispatchers.IO) {
            ram.get(key)?.also { Log.d(TAG, "RAM hit: $key") } ?:
            disk.get(key)?.also { bytes ->
                Log.d(TAG, "Disk hit: $key")
                ram.put(key, bytes, bytes.size.toLong())
                state = CacheState.LOADED
            } ?: run {
                state = CacheState.LOADING
                val bytes = runCatching { context.assets.open(assetPath).readBytes() }
                    .getOrElse { File(assetPath).takeIf { it.exists() }?.readBytes() }
                bytes?.let {
                    disk.put(key, it); ram.put(key, it, it.size.toLong())
                    state = CacheState.LOADED
                    Log.i(TAG, "Loaded $key (${it.size / 1024}KB)")
                } ?: run { state = CacheState.ERROR }
                bytes
            }
        }

    fun evictAll() { ram.evictAll(); disk.evictAll(); state = CacheState.NOT_LOADED }
    fun isLoaded() = ram.containsKey(KEY_ONNX) || disk.contains(KEY_ONNX)
    fun ramUsageBytes() = ram.currentSizeBytes()
}
