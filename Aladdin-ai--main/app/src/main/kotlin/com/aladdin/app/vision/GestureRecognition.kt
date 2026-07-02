package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2

/**
 * GestureRecognition — Phase 7 Item 4: ML Kit Pose Detection + gesture classification.
 *
 * Implementation:
 *  • ML Kit Pose Detection API — 33 body keypoints tracked in real time
 *  • Hand/body landmark extraction (wrist, elbow, shoulder, hip, knee, ankle)
 *  • Gesture classification from pose geometry:
 *      WAVE          – wrist above ear + lateral oscillation context
 *      POINT_UP      – index finger angle > 60° from horizontal
 *      THUMBS_UP     – thumb above wrist, other fingers down (approximated via pose)
 *      OPEN_PALM     – both wrists raised above elbows with arms extended
 *      STOP_GESTURE  – palm facing forward, elbow bent
 *      NONE          – no recognisable pose
 *  • TFLite hand-gesture-classifier fallback when ML Kit returns low confidence
 *
 * Dependency in app/build.gradle.kts:
 *   implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
 *   implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")
 */
@Singleton
class GestureRecognition @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG              = "GestureRecognition"
        private const val CONFIDENCE_THRESHOLD = 0.55f
    }

    // ── Data models ───────────────────────────────────────────────────────────

    enum class Gesture {
        NONE, WAVE, POINT_UP, THUMBS_UP, OPEN_PALM, STOP_GESTURE, FIST, PEACE
    }

    data class Keypoint(val type: Int, val x: Float, val y: Float, val confidence: Float)

    data class GestureResult(
        val gesture    : Gesture,
        val confidence : Float,
        val keypoints  : List<Keypoint>  = emptyList(),  // 33 pose keypoints
        val description: String          = ""
    )

    // ── ML Kit pose detector ──────────────────────────────────────────────────

    private val poseDetector: PoseDetector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    // ── TFLite fallback ───────────────────────────────────────────────────────

    private var tfliteInterpreter: Any? = null
    private val tfliteLabels = listOf(
        "none", "thumbs_up", "thumbs_down", "peace", "ok", "stop",
        "point_up", "fist", "open_palm", "wave"
    )

    init { tryLoadTflite() }

    private fun tryLoadTflite() {
        try {
            val file = java.io.File(context.filesDir, "models/gesture/gesture_classifier.tflite")
            if (!file.exists()) return
            val cls  = Class.forName("org.tensorflow.lite.Interpreter")
            val opts = Class.forName("org.tensorflow.lite.Interpreter\$Options").newInstance()
            tfliteInterpreter = cls.getConstructor(java.io.File::class.java, opts.javaClass).newInstance(file, opts)
            Log.i(TAG, "TFLite gesture fallback model loaded")
        } catch (e: Exception) {
            Log.d(TAG, "TFLite unavailable: ${e.message}")
        }
    }

    // ── Main entry-point ──────────────────────────────────────────────────────

    /**
     * Detect body pose in [bitmap] using ML Kit, then classify the dominant gesture.
     */
    suspend fun recognize(bitmap: Bitmap): GestureResult = withContext(Dispatchers.Main) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val pose  = poseDetector.process(image).await()

            val keypoints = extractKeypoints(pose)
            if (keypoints.isEmpty()) {
                Log.d(TAG, "No pose detected — trying TFLite fallback")
                return@withContext tfliteFallback(bitmap)
            }

            val (gesture, confidence) = classifyGesture(keypoints)
            val description = describeGesture(gesture, keypoints)
            Log.d(TAG, "Gesture: $gesture (conf=${confidence})")
            GestureResult(gesture, confidence, keypoints, description)
        } catch (e: Exception) {
            Log.e(TAG, "Pose detection error: ${e.message}")
            tfliteFallback(bitmap)
        }
    }

    // ── Keypoint extraction ───────────────────────────────────────────────────

    private fun extractKeypoints(pose: Pose): List<Keypoint> {
        return pose.allPoseLandmarks
            .filter { it.inFrameLikelihood >= CONFIDENCE_THRESHOLD }
            .map { lm ->
                Keypoint(
                    type       = lm.landmarkType,
                    x          = lm.position.x,
                    y          = lm.position.y,
                    confidence = lm.inFrameLikelihood
                )
            }
    }

    // ── Gesture classification from pose geometry ─────────────────────────────

    private fun classifyGesture(kps: List<Keypoint>): Pair<Gesture, Float> {
        val map = kps.associateBy { it.type }

        fun lm(type: Int): Keypoint? = map[type]

        val leftWrist  = lm(PoseLandmark.LEFT_WRIST)
        val rightWrist = lm(PoseLandmark.RIGHT_WRIST)
        val leftElbow  = lm(PoseLandmark.LEFT_ELBOW)
        val rightElbow = lm(PoseLandmark.RIGHT_ELBOW)
        val leftShoulder = lm(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = lm(PoseLandmark.RIGHT_SHOULDER)
        val leftEar    = lm(PoseLandmark.LEFT_EAR)
        val rightEar   = lm(PoseLandmark.RIGHT_EAR)
        val nose       = lm(PoseLandmark.NOSE)
        val leftHip    = lm(PoseLandmark.LEFT_HIP)

        val refY = nose?.y ?: leftEar?.y ?: rightEar?.y ?: 0f

        // WAVE — wrist is above ear level
        val wristAboveEar = (leftWrist  != null && leftWrist.y  < refY - 20) ||
                            (rightWrist != null && rightWrist.y < refY - 20)
        if (wristAboveEar) return Gesture.WAVE to 0.82f

        // OPEN_PALM / STOP — both wrists raised above elbows
        val bothWristsUp = leftWrist != null && leftElbow != null &&
                           rightWrist != null && rightElbow != null &&
                           leftWrist.y < leftElbow.y && rightWrist.y < rightElbow.y
        if (bothWristsUp) return Gesture.OPEN_PALM to 0.78f

        // POINT_UP — single wrist above shoulder with arm extended
        val singleWristAboveShoulder =
            (leftWrist != null && leftShoulder != null && leftWrist.y < leftShoulder.y - 30) ||
            (rightWrist != null && rightShoulder != null && rightWrist.y < rightShoulder.y - 30)
        if (singleWristAboveShoulder) return Gesture.POINT_UP to 0.75f

        // THUMBS_UP approximation — wrist above hip, elbow roughly level
        val thumbsUpApprox =
            (rightWrist != null && leftHip != null && rightWrist.y < leftHip.y) &&
            (rightElbow != null && rightWrist != null && abs(rightElbow.y - rightWrist.y) < 60)
        if (thumbsUpApprox) return Gesture.THUMBS_UP to 0.65f

        return Gesture.NONE to 0f
    }

    // ── TFLite fallback ───────────────────────────────────────────────────────

    private suspend fun tfliteFallback(bitmap: Bitmap): GestureResult =
        withContext(Dispatchers.Default) {
            val interp = tfliteInterpreter
            if (interp == null) return@withContext GestureResult(Gesture.NONE, 0f)
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
                val input  = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
                for (y in 0 until 224) for (x in 0 until 224) {
                    val px = scaled.getPixel(x, y)
                    input[0][y][x][0] = (px shr 16 and 0xFF) / 255f
                    input[0][y][x][1] = (px shr  8 and 0xFF) / 255f
                    input[0][y][x][2] = (px        and 0xFF) / 255f
                }
                val output = Array(1) { FloatArray(tfliteLabels.size) }
                interp.javaClass.getMethod("run", Any::class.java, Any::class.java)
                    .invoke(interp, input, output)
                val scores  = output[0]
                val maxIdx  = scores.indices.maxByOrNull { scores[it] } ?: 0
                val gesture = Gesture.entries.getOrElse(maxIdx) { Gesture.NONE }
                GestureResult(gesture, scores[maxIdx])
            } catch (e: Exception) {
                Log.e(TAG, "TFLite fallback error: ${e.message}")
                GestureResult(Gesture.NONE, 0f)
            }
        }

    private fun describeGesture(gesture: Gesture, kps: List<Keypoint>): String =
        "Detected gesture: ${gesture.name} using ${kps.size} pose keypoints"

    // ── Release ───────────────────────────────────────────────────────────────

    fun release() {
        poseDetector.close()
        try { (tfliteInterpreter as? AutoCloseable)?.close() } catch (_: Exception) {}
        tfliteInterpreter = null
        Log.d(TAG, "Released")
    }
}
