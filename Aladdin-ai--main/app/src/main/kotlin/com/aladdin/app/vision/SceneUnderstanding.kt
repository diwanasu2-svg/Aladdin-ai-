package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SceneUnderstanding — Phase 7 Item 2: Scene classification with ML Kit Image Labeling.
 *
 * Implementation:
 *  • ML Kit Image Labeling API (on-device model, confidence >= 0.5)
 *  • Confidence-scored labels (0–1) attached to each SceneLabel
 *  • Context extraction: indoor/outdoor, people, vehicles, food, nature, tech
 *  • TFLite scene-classifier fallback when ML Kit labels are insufficient
 *
 * Dependency in app/build.gradle.kts:
 *   implementation("com.google.mlkit:image-labeling:17.0.9")
 */
@Singleton
class SceneUnderstanding @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG               = "SceneUnderstanding"
        private const val MIN_CONFIDENCE    = 0.50f   // minimum label confidence
        private const val INPUT_SIZE        = 224
        private const val MAX_LABELS        = 10
    }

    // ── Data models ───────────────────────────────────────────────────────────

    data class SceneLabel(
        val label     : String,
        val confidence: Float          // 0.0 – 1.0
    )

    data class SceneAnalysis(
        val primaryScene : String,
        val confidence   : Float,
        val labels       : List<SceneLabel>,  // all confident labels
        val context      : SceneContext,
        val description  : String
    )

    enum class SceneContext {
        INDOOR, OUTDOOR_NATURE, OUTDOOR_URBAN, PEOPLE,
        FOOD, VEHICLES, TECHNOLOGY, DOCUMENT, UNKNOWN
    }

    // ── ML Kit image labeler ──────────────────────────────────────────────────

    private val labeler by lazy {
        val opts = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(MIN_CONFIDENCE)
            .build()
        ImageLabeling.getClient(opts)
    }

    // ── TFLite fallback (optional) ────────────────────────────────────────────
    private var tfliteInterpreter: Any? = null
    private var tfliteLabels: List<String> = emptyList()

    init { tryLoadTfliteFallback() }

    private fun tryLoadTfliteFallback() {
        try {
            val modelFile  = java.io.File(context.filesDir, "models/scene/scene_classifier.tflite")
            val labelFile  = java.io.File(context.filesDir, "models/scene/labels.txt")
            if (!modelFile.exists()) return
            if (labelFile.exists()) tfliteLabels = labelFile.readLines().filter { it.isNotBlank() }
            val cls  = Class.forName("org.tensorflow.lite.Interpreter")
            val opts = Class.forName("org.tensorflow.lite.Interpreter\$Options").newInstance()
            tfliteInterpreter = cls.getConstructor(java.io.File::class.java, opts.javaClass)
                .newInstance(modelFile, opts)
            Log.i(TAG, "TFLite scene fallback model loaded (${tfliteLabels.size} labels)")
        } catch (e: Exception) {
            Log.d(TAG, "TFLite scene fallback unavailable: ${e.message}")
        }
    }

    // ── Main analyse entry-point ──────────────────────────────────────────────

    /**
     * Analyse [bitmap] and return a [SceneAnalysis] with confidence-scored labels
     * and a human-readable context category.
     */
    suspend fun analyze(bitmap: Bitmap): SceneAnalysis = withContext(Dispatchers.Main) {
        try {
            val image  = InputImage.fromBitmap(bitmap, 0)
            val result = labeler.process(image).await()

            if (result.isEmpty()) {
                Log.d(TAG, "ML Kit returned no labels — trying TFLite fallback")
                return@withContext tfliteAnalyze(bitmap)
                    ?: fallbackAnalysis()
            }

            val labels = result
                .sortedByDescending { it.confidence }
                .take(MAX_LABELS)
                .map { SceneLabel(it.text, it.confidence) }

            val primary = labels.first()
            val ctx     = deriveContext(labels)
            val desc    = buildDescription(primary, labels, ctx)

            Log.d(TAG, "ML Kit scene: ${primary.label} (${primary.confidence}) ctx=$ctx")
            SceneAnalysis(primary.label, primary.confidence, labels, ctx, desc)
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit analysis error: ${e.message}")
            tfliteAnalyze(bitmap) ?: fallbackAnalysis()
        }
    }

    // ── TFLite fallback ───────────────────────────────────────────────────────

    private suspend fun tfliteAnalyze(bitmap: Bitmap): SceneAnalysis? {
        val interp = tfliteInterpreter ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
                val input  = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(3) } } }
                for (y in 0 until INPUT_SIZE) for (x in 0 until INPUT_SIZE) {
                    val px = scaled.getPixel(x, y)
                    input[0][y][x][0] = ((px shr 16 and 0xFF) - 127.5f) / 127.5f
                    input[0][y][x][1] = ((px shr  8 and 0xFF) - 127.5f) / 127.5f
                    input[0][y][x][2] = ((px        and 0xFF) - 127.5f) / 127.5f
                }
                val output = Array(1) { FloatArray(tfliteLabels.size.coerceAtLeast(1)) }
                interp.javaClass.getMethod("run", Any::class.java, Any::class.java)
                    .invoke(interp, input, output)
                val scores    = output[0]
                val topIdx    = scores.indices.sortedByDescending { scores[it] }.take(5)
                val labels    = topIdx.map { SceneLabel(tfliteLabels.getOrElse(it) { "scene" }, scores[it]) }
                val primary   = labels.first()
                SceneAnalysis(primary.label, primary.confidence, labels,
                    deriveContext(labels), "Scene: ${primary.label} (${(primary.confidence * 100).toInt()}%)")
            } catch (e: Exception) {
                Log.e(TAG, "TFLite fallback error: ${e.message}"); null
            }
        }
    }

    // ── Context classification ────────────────────────────────────────────────

    private val OUTDOOR_NATURE_KW = setOf("tree", "plant", "sky", "grass", "mountain",
        "river", "lake", "forest", "flower", "beach", "nature", "animal")
    private val OUTDOOR_URBAN_KW  = setOf("building", "road", "car", "city", "street",
        "architecture", "traffic", "bridge", "skyscraper", "bus", "taxi")
    private val INDOOR_KW         = setOf("room", "furniture", "ceiling", "floor", "wall",
        "table", "chair", "sofa", "kitchen", "bedroom", "bathroom", "office")
    private val PEOPLE_KW         = setOf("person", "people", "face", "human", "man",
        "woman", "child", "crowd", "group", "portrait")
    private val FOOD_KW           = setOf("food", "meal", "fruit", "vegetable", "drink",
        "cup", "dish", "plate", "restaurant", "cooking")
    private val VEHICLE_KW        = setOf("car", "truck", "bus", "motorcycle", "bicycle",
        "vehicle", "airplane", "boat", "train")
    private val TECH_KW           = setOf("computer", "phone", "screen", "laptop",
        "keyboard", "device", "technology", "electronic")
    private val DOC_KW            = setOf("text", "document", "paper", "book", "poster",
        "sign", "label", "logo", "handwriting")

    private fun deriveContext(labels: List<SceneLabel>): SceneContext {
        val words = labels.flatMap { it.label.lowercase().split(" ", "_") }.toSet()
        return when {
            words.any { it in PEOPLE_KW }       -> SceneContext.PEOPLE
            words.any { it in DOC_KW }          -> SceneContext.DOCUMENT
            words.any { it in FOOD_KW }         -> SceneContext.FOOD
            words.any { it in TECH_KW }         -> SceneContext.TECHNOLOGY
            words.any { it in VEHICLE_KW }      -> SceneContext.VEHICLES
            words.any { it in OUTDOOR_URBAN_KW } -> SceneContext.OUTDOOR_URBAN
            words.any { it in OUTDOOR_NATURE_KW } -> SceneContext.OUTDOOR_NATURE
            words.any { it in INDOOR_KW }       -> SceneContext.INDOOR
            else                                -> SceneContext.UNKNOWN
        }
    }

    private fun buildDescription(
        primary : SceneLabel,
        labels  : List<SceneLabel>,
        ctx     : SceneContext
    ): String {
        val ctxStr = ctx.name.lowercase().replace("_", " ")
        val others = labels.drop(1).take(3).joinToString(", ") { it.label }
        val desc   = "Scene: ${primary.label} (${(primary.confidence * 100).toInt()}%)" +
            if (others.isNotBlank()) " — also: $others" else ""
        return "$desc | Context: $ctxStr"
    }

    private fun fallbackAnalysis(): SceneAnalysis = SceneAnalysis(
        primaryScene = "scene",
        confidence   = 0f,
        labels       = emptyList(),
        context      = SceneContext.UNKNOWN,
        description  = "Scene analysis unavailable"
    )

    fun release() {
        labeler.close()
        try { (tfliteInterpreter as? AutoCloseable)?.close() } catch (_: Exception) {}
        tfliteInterpreter = null
    }
}
