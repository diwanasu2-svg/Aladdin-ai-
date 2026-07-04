package com.aladdin.assistant.agent

import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5 – Memory Agent (High Priority)
 *
 * Responsibilities:
 *  - Save and retrieve memories (short-term and long-term)
 *  - Merge duplicate memories
 *  - Update importance scores
 *  - Semantic search over stored memories
 *  - Synchronise memory state across agents
 */
class MemoryAgent {

    companion object {
        private const val TAG = "MemoryAgent"
        private const val SHORT_TERM_MAX = 50
        private const val LONG_TERM_MAX = 1000
        private const val SIMILARITY_THRESHOLD = 0.75
    }

    // ── Memory model ────────────────────────────────────────────────────────

    enum class MemoryType { SHORT_TERM, LONG_TERM, EPISODIC, SEMANTIC }

    data class Memory(
        val id: String = UUID.randomUUID().toString(),
        val content: String,
        val type: MemoryType = MemoryType.SHORT_TERM,
        val tags: List<String> = emptyList(),
        val importance: Float = 0.5f,          // 0.0 … 1.0
        val embedding: List<Float> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastAccessedAt: Long = System.currentTimeMillis(),
        var accessCount: Int = 0
    )

    // ── Storage ─────────────────────────────────────────────────────────────

    private val shortTermMemory = ConcurrentHashMap<String, Memory>()
    private val longTermMemory  = ConcurrentHashMap<String, Memory>()
    private val episodicMemory  = ConcurrentHashMap<String, Memory>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.MEMORY ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Memory Agent started")
    }

    // ── Core operations ─────────────────────────────────────────────────────

    /** Save a memory — auto-promotes to long-term if importance ≥ 0.8. */
    fun save(
        content: String,
        type: MemoryType = MemoryType.SHORT_TERM,
        tags: List<String> = emptyList(),
        importance: Float = 0.5f
    ): Memory {
        val memory = Memory(
            content = content,
            type = type,
            tags = tags,
            importance = importance,
            embedding = generateSimpleEmbedding(content)
        )

        when {
            importance >= 0.8f || type == MemoryType.LONG_TERM -> {
                evictIfNeeded(longTermMemory, LONG_TERM_MAX)
                longTermMemory[memory.id] = memory.copy(type = MemoryType.LONG_TERM)
            }
            type == MemoryType.EPISODIC -> {
                episodicMemory[memory.id] = memory
            }
            else -> {
                evictIfNeeded(shortTermMemory, SHORT_TERM_MAX)
                shortTermMemory[memory.id] = memory
            }
        }

        // Check for duplicates and merge
        mergeDuplicates(memory)

        Log.d(TAG, "Saved memory [${memory.type}] importance=$importance '${content.take(60)}'")
        return memory
    }

    /** Retrieve a memory by ID (searches all stores). */
    fun retrieve(id: String): Memory? {
        val memory = shortTermMemory[id] ?: longTermMemory[id] ?: episodicMemory[id]
        memory?.let {
            val updated = it.copy(
                lastAccessedAt = System.currentTimeMillis(),
                accessCount = it.accessCount + 1
            )
            updateInStore(updated)
            return updated
        }
        return null
    }

    /** Semantic search — returns top-k memories most similar to the query. */
    fun semanticSearch(query: String, topK: Int = 5, minImportance: Float = 0.0f): List<Memory> {
        val queryEmbedding = generateSimpleEmbedding(query)
        val allMemories = shortTermMemory.values + longTermMemory.values + episodicMemory.values

        return allMemories
            .filter { it.importance >= minImportance }
            .map { memory ->
                val sim = cosineSimilarity(queryEmbedding, memory.embedding)
                Pair(memory, sim)
            }
            .filter { (_, sim) -> sim >= SIMILARITY_THRESHOLD || topK <= 3 }
            .sortedByDescending { (_, sim) -> sim }
            .take(topK)
            .map { (memory, _) ->
                memory.copy(
                    lastAccessedAt = System.currentTimeMillis(),
                    accessCount = memory.accessCount + 1
                ).also { updateInStore(it) }
            }
    }

    /** Keyword/tag search. */
    fun search(query: String, tags: List<String> = emptyList()): List<Memory> {
        val queryLower = query.lowercase()
        val allMemories = shortTermMemory.values + longTermMemory.values + episodicMemory.values
        return allMemories.filter { memory ->
            val contentMatch = memory.content.lowercase().contains(queryLower)
            val tagMatch = tags.isEmpty() || memory.tags.any { it.lowercase() in tags.map { t -> t.lowercase() } }
            contentMatch && tagMatch
        }.sortedByDescending { it.importance }
    }

    /** Update importance score for a stored memory. */
    fun updateImportance(id: String, newImportance: Float) {
        val memory = shortTermMemory[id] ?: longTermMemory[id] ?: return
        val updated = memory.copy(importance = newImportance.coerceIn(0f, 1f))
        updateInStore(updated)

        // Promote to long-term if now high importance
        if (newImportance >= 0.8f && shortTermMemory.containsKey(id)) {
            shortTermMemory.remove(id)
            longTermMemory[id] = updated.copy(type = MemoryType.LONG_TERM)
            Log.d(TAG, "Promoted memory $id to long-term (importance=$newImportance)")
        }
    }

    /** Merge duplicate or near-duplicate memories into one. */
    fun mergeDuplicates(newMemory: Memory) {
        val existing = shortTermMemory.values + longTermMemory.values
        existing.forEach { stored ->
            if (stored.id != newMemory.id) {
                val sim = cosineSimilarity(stored.embedding, newMemory.embedding)
                if (sim > 0.95) {
                    // Merge: keep higher-importance version, combine tags
                    val mergedTags = (stored.tags + newMemory.tags).distinct()
                    val mergedImportance = maxOf(stored.importance, newMemory.importance)
                    val merged = stored.copy(tags = mergedTags, importance = mergedImportance)
                    updateInStore(merged)
                    // Remove duplicate new memory
                    shortTermMemory.remove(newMemory.id)
                    longTermMemory.remove(newMemory.id)
                    Log.d(TAG, "Merged duplicate memory (sim=$sim)")
                }
            }
        }
    }

    /** Forget (delete) a memory. */
    fun forget(id: String) {
        shortTermMemory.remove(id)
        longTermMemory.remove(id)
        episodicMemory.remove(id)
    }

    /** Summarise all memories — returns top long-term + recent short-term. */
    fun getSummary(maxItems: Int = 10): List<Memory> {
        val longTerm = longTermMemory.values.sortedByDescending { it.importance }.take(maxItems / 2)
        val shortTerm = shortTermMemory.values.sortedByDescending { it.lastAccessedAt }.take(maxItems / 2)
        return longTerm + shortTerm
    }

    fun getStats() = mapOf(
        "short_term" to shortTermMemory.size,
        "long_term" to longTermMemory.size,
        "episodic" to episodicMemory.size
    )

    // ── Message handler ─────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        when (msg.type) {
            AgentCommunication.MessageType.MEMORY_SYNC -> {
                val content = msg.payload["content"]?.toString() ?: return
                val importance = (msg.payload["importance"] as? Double)?.toFloat() ?: 0.5f
                val saved = save(content, importance = importance)
                AgentCommunication.reportResult(
                    sender = AgentCommunication.AgentType.MEMORY,
                    receiver = msg.sender,
                    taskId = msg.taskId,
                    result = mapOf("memoryId" to saved.id)
                )
            }
            AgentCommunication.MessageType.TASK_REQUEST -> {
                val query = msg.payload["query"]?.toString() ?: return
                val results = semanticSearch(query)
                AgentCommunication.reportResult(
                    sender = AgentCommunication.AgentType.MEMORY,
                    receiver = msg.sender,
                    taskId = msg.taskId,
                    result = results.map { it.content }
                )
            }
            else -> {}
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun evictIfNeeded(store: ConcurrentHashMap<String, Memory>, max: Int) {
        if (store.size >= max) {
            val evict = store.values
                .sortedWith(compareBy({ it.importance }, { it.lastAccessedAt }))
                .take(store.size - max + 1)
            evict.forEach { store.remove(it.id) }
        }
    }

    private fun updateInStore(memory: Memory) {
        when {
            shortTermMemory.containsKey(memory.id) -> shortTermMemory[memory.id] = memory
            longTermMemory.containsKey(memory.id)  -> longTermMemory[memory.id] = memory
            episodicMemory.containsKey(memory.id)  -> episodicMemory[memory.id] = memory
        }
    }

    /** Lightweight bag-of-words embedding (no ML required on-device). */
    private fun generateSimpleEmbedding(text: String): List<Float> {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val dim = 64
        val vec = FloatArray(dim) { 0f }
        words.forEach { word ->
            var hash = word.hashCode()
            for (i in 0 until minOf(word.length, 4)) {
                val idx = (Math.abs(hash) % dim)
                vec[idx] += 1f
                hash = hash * 31 + word[i].code
            }
        }
        // L2-normalise
        val norm = Math.sqrt(vec.map { it * it }.sum().toDouble()).toFloat().coerceAtLeast(1e-8f)
        return vec.map { it / norm }
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom < 1e-8) 0f else (dot / denom).toFloat()
    }
}
