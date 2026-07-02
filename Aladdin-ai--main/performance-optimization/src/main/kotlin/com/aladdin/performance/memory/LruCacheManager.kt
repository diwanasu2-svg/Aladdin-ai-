package com.aladdin.performance.memory

import android.util.LruCache
import android.util.Log

/**
 * Typed LRU caches for runtime data (not model files).
 * Covers: AI responses, TTS audio clips, tool results.
 */
class LruCacheManager {

    companion object { private const val TAG = "LruCacheManager" }

    /** Cache for text AI responses keyed by query hash */
    val responseCache = object : LruCache<String, String>(200) {
        override fun sizeOf(key: String, value: String) = value.length / 512 + 1
        override fun entryRemoved(evicted: Boolean, key: String, old: String, new: String?) {
            if (evicted) Log.v(TAG, "Response evicted: ${key.take(8)}…")
        }
    }

    /** Cache for synthesised TTS audio (PCM shorts) keyed by text hash */
    val ttsAudioCache = object : LruCache<String, ShortArray>(50 * 1024) {  // 50K shorts ≈ 100KB
        override fun sizeOf(key: String, value: ShortArray) = value.size / 1024 + 1
    }

    /** Cache for tool/function call results keyed by call signature */
    val toolResultCache = object : LruCache<String, String>(100) {
        override fun sizeOf(key: String, value: String) = 1
    }

    /** Cache for embeddings (sentence → FloatArray) */
    val embeddingCache = object : LruCache<String, FloatArray>(500) {
        override fun sizeOf(key: String, value: FloatArray) = value.size / 128 + 1
    }

    fun trimAll() {
        responseCache.trimToSize(responseCache.maxSize() / 2)
        ttsAudioCache.trimToSize(ttsAudioCache.maxSize() / 2)
        toolResultCache.trimToSize(toolResultCache.maxSize() / 2)
        embeddingCache.trimToSize(embeddingCache.maxSize() / 2)
        Log.i(TAG, "All LRU caches trimmed to 50%")
    }

    fun evictAll() {
        responseCache.evictAll(); ttsAudioCache.evictAll()
        toolResultCache.evictAll(); embeddingCache.evictAll()
        Log.i(TAG, "All LRU caches cleared")
    }

    fun stats() = buildString {
        appendLine("LRU Cache Stats:")
        appendLine("  Responses:  ${responseCache.size()}/${responseCache.maxSize()} hits=${responseCache.hitCount()} miss=${responseCache.missCount()}")
        appendLine("  TTS Audio:  ${ttsAudioCache.size()}/${ttsAudioCache.maxSize()} hits=${ttsAudioCache.hitCount()} miss=${ttsAudioCache.missCount()}")
        appendLine("  Tool Results: ${toolResultCache.size()}/${toolResultCache.maxSize()}")
        appendLine("  Embeddings: ${embeddingCache.size()}/${embeddingCache.maxSize()} hits=${embeddingCache.hitCount()} miss=${embeddingCache.missCount()}")
    }
}
