package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * FaceRecognition — Phase 7 Item 1: Face detection + embedding generation + matching.
 *
 * Implementation:
 *  • Face Detection  → ML Kit Face Detection API (accurate mode, all contours)
 *  • Face Embeddings → 128-dim float array (pixel-hash + TFLite if model present)
 *  • Face Matching   → cosine-similarity, threshold 0.70 for recognition
 *  • Camera overlay  → exposes detected bounding boxes as FaceDetectionResult
 *
 * Dependency in app/build.gradle.kts:
 *   implementation("com.google.mlkit:face-detection:16.1.7")
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
 */
@Singleton
class FaceRecognition @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG             = "FaceRecognition"
        private const val EMBEDDING_DIM   = 128
        private const val MATCH_THRESHOLD = 0.70f   // Phase 7 requirement: 0.7
        private const val MODEL_DIR       = "models/face"
        private const val EMBED_MODEL     = "face_embedding.tflite"
    }

    // ── Data models ───────────────────────────────────────────────────────────

    data class FaceDetectionResult(
        val boundingBox: RectF,
        val confidence : Float,
        val landmarks  : List<android.graphics.PointF> = emptyList(),
        val trackingId : Int = -1
    )

    data class FaceEmbedding(
        val id        : String,
        val name      : String,
        val embedding : FloatArray
    )

    data class FaceMatch(
        val name      : String,
        val similarity: Float,
        val isMatch   : Boolean
    )

    // ── ML Kit face detector ──────────────────────────────────────────────────

    private val mlKitDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    // ── Optional TFLite embedding interpreter ─────────────────────────────────
    private var embeddingInterpreter: Any? = null
    private val knownFaces = mutableListOf<FaceEmbedding>()

    init { tryLoadTflite() }

    private fun tryLoadTflite() {
        try {
            val file = File(context.filesDir, "$MODEL_DIR/$EMBED_MODEL")
            if (!file.exists()) { Log.w(TAG, "TFLite embedding model absent — pixel-hash fallback"); return }
            val cls  = Class.forName("org.tensorflow.lite.Interpreter")
            val opts = Class.forName("org.tensorflow.lite.Interpreter\$Options").newInstance()
            embeddingInterpreter = cls.getConstructor(File::class.java, opts.javaClass).newInstance(file, opts)
            Log.i(TAG, "TFLite face embedding model loaded")
        } catch (e: Exception) {
            Log.w(TAG, "TFLite not available: ${e.message}")
        }
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    /**
     * Detect all faces in [bitmap] using ML Kit Face Detection.
     * Returns bounding boxes + landmark points for CameraX overlay.
     */
    suspend fun detectFaces(bitmap: Bitmap): List<FaceDetectionResult> =
        withContext(Dispatchers.Main) {          // ML Kit tasks run on main by default
            try {
                val image  = InputImage.fromBitmap(bitmap, 0)
                val faces  = mlKitDetector.process(image).await()
                faces.map { face: Face ->
                    val box = face.boundingBox
                    FaceDetectionResult(
                        boundingBox = RectF(
                            box.left.toFloat(), box.top.toFloat(),
                            box.right.toFloat(), box.bottom.toFloat()
                        ),
                        confidence  = face.smilingProbability ?: 0.85f,
                        landmarks   = face.allLandmarks.map { lm ->
                            android.graphics.PointF(lm.position.x, lm.position.y)
                        },
                        trackingId  = face.trackingId ?: -1
                    )
                }.also { Log.d(TAG, "ML Kit detected ${it.size} face(s)") }
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit detection error: ${e.message}")
                emptyList()
            }
        }

    // ── Embedding generation ──────────────────────────────────────────────────

    /**
     * Generate a 128-dim normalised embedding for the given [bitmap].
     * Uses TFLite model when available; falls back to pixel-hash method.
     */
    suspend fun generateEmbedding(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        if (embeddingInterpreter != null) {
            tfliteEmbedding(bitmap)
        } else {
            pixelHashEmbedding(bitmap)
        }
    }

    private fun tfliteEmbedding(bitmap: Bitmap): FloatArray {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 112, 112, true)
            val input  = Array(1) { Array(112) { Array(112) { FloatArray(3) } } }
            for (y in 0 until 112) for (x in 0 until 112) {
                val px = scaled.getPixel(x, y)
                input[0][y][x][0] = (px shr 16 and 0xFF) / 255f
                input[0][y][x][1] = (px shr  8 and 0xFF) / 255f
                input[0][y][x][2] = (px        and 0xFF) / 255f
            }
            val output = Array(1) { FloatArray(EMBEDDING_DIM) }
            embeddingInterpreter!!.javaClass
                .getMethod("run", Any::class.java, Any::class.java)
                .invoke(embeddingInterpreter, input, output)
            normalize(output[0])
        } catch (e: Exception) {
            Log.e(TAG, "TFLite embedding error: ${e.message}")
            pixelHashEmbedding(bitmap)
        }
    }

    /** Deterministic pixel-hash embedding used when TFLite model is absent. */
    private fun pixelHashEmbedding(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val emb    = FloatArray(EMBEDDING_DIM)
        for (y in 0 until 64) for (x in 0 until 64) {
            val px = scaled.getPixel(x, y)
            val i  = (y * 64 + x) % EMBEDDING_DIM
            emb[i] += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / (3 * 255f)
        }
        return normalize(emb)
    }

    // ── Known-face store ──────────────────────────────────────────────────────

    fun addKnownFace(name: String, embedding: FloatArray) {
        val id  = name.lowercase().replace(" ", "_")
        val idx = knownFaces.indexOfFirst { it.id == id }
        val entry = FaceEmbedding(id, name, embedding)
        if (idx >= 0) knownFaces[idx] = entry else knownFaces.add(entry)
        Log.d(TAG, "Known face stored: $name (total=${knownFaces.size})")
    }

    fun identify(embedding: FloatArray): FaceMatch {
        if (knownFaces.isEmpty()) return FaceMatch("Unknown", 0f, false)
        var bestName = "Unknown"; var bestSim = 0f
        knownFaces.forEach { face ->
            val sim = cosineSimilarity(embedding, face.embedding)
            if (sim > bestSim) { bestSim = sim; bestName = face.name }
        }
        return FaceMatch(bestName, bestSim, bestSim >= MATCH_THRESHOLD)
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.map { it * it }.sum())
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    // ── Release ───────────────────────────────────────────────────────────────

    fun release() {
        mlKitDetector.close()
        try { (embeddingInterpreter as? AutoCloseable)?.close() } catch (_: Exception) {}
        embeddingInterpreter = null
        Log.d(TAG, "Released")
    }
}
