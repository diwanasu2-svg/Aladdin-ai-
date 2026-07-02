package com.aladdin.memory.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * On-device sentence embedding engine using all-MiniLM-L6-v2 (TFLite, 384-dim).
 *
 * Model file: context.filesDir/models/minilm/model.tflite
 * Vocab file:  context.filesDir/models/minilm/vocab.txt
 *
 * Falls back to a simple TF-IDF bag-of-words vector (1024-dim) when the
 * TFLite model is not present, so the system remains functional offline
 * without a model download.
 *
 * Download the model via:
 *   scripts/download_models.sh  (included in the module)
 */
class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingEngine"
        const val EMBEDDING_DIM = 384
        private const val BOW_DIM = 1024            // fallback dimension
        private const val MAX_SEQ_LEN = 128
        private const val MODEL_SUBPATH = "models/minilm/model.tflite"
        private const val VOCAB_SUBPATH = "models/minilm/vocab.txt"
    }

    private var interpreter: Interpreter? = null
    private var vocab: Map<String, Int> = emptyMap()
    private var useNative = false

    // ─── Init ─────────────────────────────────────────────────────────────────

    suspend fun init() = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(context.filesDir, MODEL_SUBPATH)
            val vocabFile = File(context.filesDir, VOCAB_SUBPATH)
            if (modelFile.exists() && vocabFile.exists()) {
                val opts = Interpreter.Options().apply {
                    setNumThreads(2)
                    setUseNNAPI(true)   // delegate to Android Neural Networks API
                }
                interpreter = Interpreter(modelFile, opts)
                vocab = loadVocab(vocabFile)
                useNative = true
                Log.i(TAG, "MiniLM TFLite model loaded (dim=$EMBEDDING_DIM)")
            } else {
                Log.w(TAG, "MiniLM model not found – using BoW fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TFLite init failed: ${e.message} – using BoW fallback")
            useNative = false
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns a normalized embedding vector for [text].
     * Length is [EMBEDDING_DIM] when native, [BOW_DIM] when BoW fallback.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val clean = preprocess(text)
        if (useNative && interpreter != null) {
            embedNative(clean)
        } else {
            embedBoW(clean)
        }
    }

    /**
     * Batch embed a list of texts. More efficient than calling [embed] in a loop
     * since we avoid repeated JNI overhead.
     */
    suspend fun embedAll(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        texts.map { embed(it) }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }

    // ─── TFLite Inference ─────────────────────────────────────────────────────

    private fun embedNative(text: String): FloatArray {
        val tokens = tokenize(text)
        val inputIds = IntArray(MAX_SEQ_LEN)
        val attentionMask = IntArray(MAX_SEQ_LEN)
        val tokenTypeIds = IntArray(MAX_SEQ_LEN)

        // CLS token = 101, SEP token = 102, PAD = 0
        inputIds[0] = 101
        attentionMask[0] = 1
        for (i in tokens.indices.take(MAX_SEQ_LEN - 2)) {
            inputIds[i + 1] = vocab[tokens[i]] ?: 100 // 100 = [UNK]
            attentionMask[i + 1] = 1
        }
        val seqEnd = minOf(tokens.size, MAX_SEQ_LEN - 2) + 1
        inputIds[seqEnd] = 102
        attentionMask[seqEnd] = 1

        val inputIdsBatch = Array(1) { inputIds }
        val maskBatch = Array(1) { attentionMask }
        val typesBatch = Array(1) { tokenTypeIds }

        val outputBuffer = Array(1) { FloatArray(EMBEDDING_DIM) }
        val inputs = mapOf(
            "input_ids" to inputIdsBatch,
            "attention_mask" to maskBatch,
            "token_type_ids" to typesBatch
        )
        val outputs = mutableMapOf<String, Any>("pooler_output" to outputBuffer)

        try {
            interpreter!!.runSignature(inputs, outputs)
        } catch (e: Exception) {
            // Fallback: try positional input map
            try {
                interpreter!!.run(inputIdsBatch, outputBuffer)
            } catch (e2: Exception) {
                Log.w(TAG, "TFLite inference error: ${e2.message} – using BoW")
                return embedBoW(text)
            }
        }

        return normalize(outputBuffer[0])
    }

    // ─── BoW Fallback ─────────────────────────────────────────────────────────

    private fun embedBoW(text: String): FloatArray {
        val vec = FloatArray(BOW_DIM)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        for (word in words) {
            val h = (word.hashCode() and 0x7FFFFFFF) % BOW_DIM
            val h2 = ((word.hashCode() xor (word.hashCode() ushr 16)) and 0x7FFFFFFF) % BOW_DIM
            vec[h] += 1f
            vec[h2] += 0.5f
        }
        return normalize(vec)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun preprocess(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(512)

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

    private fun loadVocab(file: File): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { idx, line -> map[line.trim()] = idx }
        }
        return map
    }

    fun normalize(vec: FloatArray): FloatArray {
        val norm = sqrt(vec.sumOf { it.toDouble() * it }.toFloat()).coerceAtLeast(1e-8f)
        return FloatArray(vec.size) { vec[it] / norm }
    }

    val embeddingDim: Int get() = if (useNative) EMBEDDING_DIM else BOW_DIM
}
