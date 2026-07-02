package com.aladdin.memory.engine

import android.util.Log
import kotlin.math.ln

/**
 * BM25 (Okapi BM25) ranking engine for keyword-based memory retrieval.
 *
 * Fix: invertedIndex now stores raw term counts (Int).
 * Normalization is applied only during search() to preserve BM25 formula accuracy.
 */
class BM25Engine(private val k1: Float = 1.5f, private val b: Float = 0.75f) {

    companion object {
        private const val TAG = "BM25Engine"

        private val STOP_WORDS = setOf(
            "a","an","the","is","it","in","on","at","to","of","and","or","but",
            "for","with","this","that","was","are","be","been","have","has","had",
            "do","does","did","will","would","could","should","may","might","shall",
            "from","by","about","into","through","during","before","after","above"
        )
    }

    /** Inverted index: term → map of (docId → raw term count). */
    private val invertedIndex = HashMap<String, HashMap<Long, Int>>()

    private val docLengths = HashMap<Long, Int>()
    private var totalDocs = 0
    private var avgDocLength = 0.0

    // ─── Index Management ─────────────────────────────────────────────────────

    fun upsert(id: Long, text: String) {
        remove(id)
        val terms = tokenize(text)
        if (terms.isEmpty()) return

        // Store raw term counts
        val rawCounts = HashMap<String, Int>()
        for (term in terms) rawCounts[term] = (rawCounts[term] ?: 0) + 1

        for ((term, count) in rawCounts) {
            invertedIndex.getOrPut(term) { HashMap() }[id] = count
        }

        docLengths[id] = terms.size
        totalDocs++
        updateAvgLength()
    }

    fun remove(id: Long) {
        docLengths.remove(id) ?: return
        invertedIndex.values.forEach { it.remove(id) }
        totalDocs = (totalDocs - 1).coerceAtLeast(0)
        updateAvgLength()
    }

    fun clear() {
        invertedIndex.clear()
        docLengths.clear()
        totalDocs = 0
        avgDocLength = 0.0
    }

    val size: Int get() = totalDocs

    // ─── Search ───────────────────────────────────────────────────────────────

    fun search(query: String, k: Int = 20): List<BM25Result> {
        if (totalDocs == 0) return emptyList()
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        val scores = HashMap<Long, Float>()
        val n = totalDocs.toDouble()

        for (term in queryTerms) {
            val postings = invertedIndex[term] ?: continue
            val df = postings.size.toDouble()
            val idf = ln((n - df + 0.5) / (df + 0.5) + 1.0).toFloat()

            for ((docId, rawCount) in postings) {
                val docLen = docLengths[docId] ?: 1
                // Apply BM25 normalization at query time using raw counts
                val tf = rawCount.toFloat()
                val tfNorm = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLen / avgDocLength))
                scores[docId] = (scores[docId] ?: 0f) + idf * tfNorm.toFloat()
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(k)
            .map { BM25Result(it.key, it.value) }
    }

    fun searchAmong(query: String, candidateIds: Set<Long>, k: Int = 20): List<BM25Result> =
        search(query, k * 2).filter { it.id in candidateIds }.take(k)

    fun computeTfMap(text: String): Map<String, String> {
        val terms = tokenize(text)
        val tf = HashMap<String, Int>()
        for (term in terms) tf[term] = (tf[term] ?: 0) + 1
        return tf.mapValues { it.value.toString() }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in STOP_WORDS }
            .map { stem(it) }

    private fun stem(word: String): String {
        var w = word
        if (w.endsWith("ies") && w.length > 4) w = w.dropLast(3) + "y"
        else if (w.endsWith("sses") || w.endsWith("xes") || w.endsWith("shes") || w.endsWith("ches")) w = w.dropLast(2)
        else if (w.endsWith("s") && !w.endsWith("ss") && w.length > 3) w = w.dropLast(1)
        if (w.endsWith("ing") && w.length > 5) w = w.dropLast(3)
        else if (w.endsWith("ed") && w.length > 4) w = w.dropLast(2)
        return w
    }

    // Fix 31: return 0.0 (not 1.0) when index is empty
    private fun updateAvgLength() {
        avgDocLength = if (docLengths.isEmpty()) 0.0
        else docLengths.values.average()
    }

    data class BM25Result(val id: Long, val score: Float)
}
