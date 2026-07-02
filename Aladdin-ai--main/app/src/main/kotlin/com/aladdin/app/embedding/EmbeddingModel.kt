package com.aladdin.app.embedding

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * EmbeddingModel — Sentence embeddings using MiniLM-L6-v2 (TFLite).
 *
 * Model file:
 *   Download from: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
 *   Export to TFLite format using optimum or tensorflow-lite-support.
 *   Place at: {filesDir}/models/minilm/minilm-l6-v2.tflite
 *   Config at: {filesDir}/models/minilm/vocab.txt
 *
 * Alternative — use the ModelDownloader to fetch it automatically.
 *
 * Output: 384-dimensional float embedding vector (normalized).
 *
 * When TFLite is not available, falls back to simple bag-of-words embeddings
 * so the memory system still works (with reduced semantic accuracy).
 */
@Singleton
class EmbeddingModel @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EmbeddingModel"
        private const val MODEL_SUBDIR = "models/minilm"
        private const val MODEL_FILENAME = "minilm-l6-v2.tflite"
        private const val VOCAB_FILENAME = "vocab.txt"
        private const val EMBEDDING_DIM = 384
        private const val MAX_SEQ_LENGTH = 128
    }

    private var tfliteInterpreter: Any? = null  // org.tensorflow.lite.Interpreter
    private var vocab: Map<String, Int> = emptyMap()
    private var isModelLoaded = false
    private var useFallback = false

    // ─── Initialization ───────────────────────────────────────────────────────

    fun init(): Boolean {
        val modelDir = File(context.filesDir, MODEL_SUBDIR)
        val modelFile = File(modelDir, MODEL_FILENAME)
        val vocabFile = File(modelDir, VOCAB_FILENAME)

        if (!modelFile.exists()) {
            Log.w(TAG, "MiniLM TFLite model not found at ${modelFile.absolutePath}. " +
                "Run ModelDownloader to fetch it. Using BOW fallback.")
            useFallback = true
            isModelLoaded = true
            return true
        }

        return try {
            loadVocab(vocabFile)
            loadTFLiteModel(modelFile)
            isModelLoaded = true
            Log.i(TAG, "MiniLM TFLite model loaded (${EMBEDDING_DIM}D embeddings)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}. Using BOW fallback.", e)
            useFallback = true
            isModelLoaded = true
            true
        }
    }

    fun release() {
        try {
            (tfliteInterpreter as? AutoCloseable)?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TFLite interpreter: ${e.message}")
        }
        tfliteInterpreter = null
        isModelLoaded = false
        Log.d(TAG, "EmbeddingModel released")
    }

    // ─── Embedding Generation ─────────────────────────────────────────────────

    /**
     * Generate a normalized embedding vector for the given text.
     *
     * @param text Input sentence or phrase
     * @return FloatArray of size [EMBEDDING_DIM] (normalized to unit length)
     */
    fun embed(text: String): FloatArray {
        if (!isModelLoaded) {
            Log.w(TAG, "Model not initialized — call init() first")
            return FloatArray(EMBEDDING_DIM)
        }

        return if (useFallback || tfliteInterpreter == null) {
            bowEmbedding(text)
        } else {
            try {
                tfliteEmbed(text)
            } catch (e: Exception) {
                Log.w(TAG, "TFLite inference failed: ${e.message} — falling back to BOW")
                bowEmbedding(text)
            }
        }
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     * Returns value in [-1, 1] where 1 = identical, 0 = orthogonal, -1 = opposite.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimensions must match" }
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f
        else dot / (sqrt(normA) * sqrt(normB))
    }

    /**
     * Find the most semantically similar text from a list.
     * @return Pair of (index, similarity score)
     */
    fun findMostSimilar(query: String, candidates: List<String>): Pair<Int, Float> {
        val queryEmbed = embed(query)
        var bestIdx = -1
        var bestScore = -1f
        candidates.forEachIndexed { index, candidate ->
            val score = cosineSimilarity(queryEmbed, embed(candidate))
            if (score > bestScore) {
                bestScore = score
                bestIdx = index
            }
        }
        return Pair(bestIdx, bestScore)
    }

    val isModelReady: Boolean get() = isModelLoaded
    val isUsingFallback: Boolean get() = useFallback

    // ─── TFLite Inference ─────────────────────────────────────────────────────

    private fun loadTFLiteModel(modelFile: File) {
        val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
        val optionsClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
        val options = optionsClass.newInstance()
        tfliteInterpreter = interpreterClass.getConstructor(File::class.java, optionsClass)
            .newInstance(modelFile, options)
        Log.d(TAG, "TFLite interpreter created")
    }

    private fun tfliteEmbed(text: String): FloatArray {
        val tokens = tokenize(text)
        val inputIds = IntArray(MAX_SEQ_LENGTH)
        val attentionMask = IntArray(MAX_SEQ_LENGTH)
        val tokenTypeIds = IntArray(MAX_SEQ_LENGTH)

        tokens.take(MAX_SEQ_LENGTH).forEachIndexed { i, tokenId ->
            inputIds[i] = tokenId
            attentionMask[i] = 1
        }

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        val inputs = arrayOf(inputIds, attentionMask, tokenTypeIds)

        val runMethod = tfliteInterpreter!!.javaClass.getMethod("run", Any::class.java, Any::class.java)
        runMethod.invoke(tfliteInterpreter, inputs, output)

        return normalize(output[0])
    }

    // ─── Tokenization ─────────────────────────────────────────────────────────

    private fun loadVocab(vocabFile: File) {
        if (!vocabFile.exists()) {
            Log.w(TAG, "Vocab file not found — using char-based fallback tokenizer")
            return
        }
        val vocabMap = mutableMapOf<String, Int>()
        vocabFile.useLines { lines ->
            lines.forEachIndexed { index, token ->
                vocabMap[token.trim()] = index
            }
        }
        vocab = vocabMap
        Log.d(TAG, "Vocab loaded: ${vocab.size} tokens")
    }

    private fun tokenize(text: String): IntArray {
        if (vocab.isEmpty()) return simpleCharTokenize(text)

        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val tokens = mutableListOf(vocab["[CLS]"] ?: 101)
        for (word in words) {
            val id = vocab[word] ?: vocab["[UNK]"] ?: 100
            tokens.add(id)
            if (tokens.size >= MAX_SEQ_LENGTH - 1) break
        }
        tokens.add(vocab["[SEP]"] ?: 102)
        return tokens.toIntArray()
    }

    private fun simpleCharTokenize(text: String): IntArray {
        return text.take(MAX_SEQ_LENGTH).map { it.code % 30000 }.toIntArray()
    }

    // ─── Fallback BOW Embedding ───────────────────────────────────────────────

    /**
     * Bag-of-words fallback embedding.
     * Uses a deterministic hash to map words to embedding positions.
     * Quality is lower than MiniLM but provides functional semantic search.
     */
    private fun bowEmbedding(text: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIM)
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }

        for (word in words) {
            val hash = word.hashCode()
            val primaryIdx = ((hash and 0x7FFFFFFF) % EMBEDDING_DIM)
            val secondaryIdx = (((hash ushr 8) and 0x7FFFFFFF) % EMBEDDING_DIM)
            val tertiaryIdx = (((hash ushr 16) and 0x7FFFFFFF) % EMBEDDING_DIM)
            embedding[primaryIdx] += 1f
            embedding[secondaryIdx] += 0.5f
            embedding[tertiaryIdx] += 0.25f
        }

        return normalize(embedding)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.map { it * it }.sum())
        return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
    }
}
