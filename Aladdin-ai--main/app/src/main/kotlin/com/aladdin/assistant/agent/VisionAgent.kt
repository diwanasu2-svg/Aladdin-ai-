import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 5 – Vision Agent (Medium Priority)
 *
 * Responsibilities:
 *  - Analyse camera images and screenshots
 *  - Perform OCR (Optical Character Recognition) via ML Kit
 *  - Identify objects via ML Kit Object Detection
 *  - Analyse documents and extract text
 *  - Send vision results to the reasoning/coordinator system
 */
@Singleton
class VisionAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryAgent: MemoryAgent,
    private val safetyAgent: SafetyAgent
) {
    companion object {
        private const val TAG = "VisionAgent"
    }

    // ── Models ────────────────────────────────────────────────────────────────

    data class VisionResult(
        val type: AnalysisType,
        val text: String = "",
        val objects: List<DetectedObject> = emptyList(),
        val faces: List<FaceInfo> = emptyList(),
        val documentType: String = "",
        val confidence: Float = 0f,
        val rawDescription: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    data class DetectedObject(
        val label: String,
        val confidence: Float,
        val boundingBox: BoundingBox?
    )

    data class FaceInfo(
        val index: Int,
        val smiling: Boolean,
        val eyesOpen: Boolean,
        val boundingBox: BoundingBox?
    )

    data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    enum class AnalysisType { OCR, OBJECT_DETECTION, FACE_RECOGNITION, DOCUMENT, SCREENSHOT, GENERAL }

    // ── ML Kit components ─────────────────────────────────────────────────────

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val objectDetectorOptions = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .enableMultipleObjects()
        .enableClassification()
        .build()
    private val objectDetector = ObjectDetection.getClient(objectDetectorOptions)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.VISION ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Vision Agent started")
    }

    // ── Public analysis API ───────────────────────────────────────────────────

    /** Full analysis: auto-selects best pipeline based on content. */
    suspend fun analyse(bitmap: Bitmap): VisionResult {
        val ocr = performOCR(bitmap)
        val objects = detectObjects(bitmap)

        val type = when {
            ocr.text.length > 50 -> AnalysisType.DOCUMENT
            objects.isNotEmpty() -> AnalysisType.OBJECT_DETECTION
            else                 -> AnalysisType.GENERAL
        }

        val result = VisionResult(
            type = type,
            text = ocr.text,
            objects = objects,
            confidence = (ocr.confidence + (objects.firstOrNull()?.confidence ?: 0f)) / 2f,
            rawDescription = buildDescription(ocr.text, objects)
        )

        rememberResult(result)
        return result
    }

    /** Analyse from a file path. */
    suspend fun analyseFile(path: String): VisionResult = withContext(Dispatchers.IO) {
        val safety = safetyAgent.validate(path)
        if (!safety.isSafe) {
            return@withContext VisionResult(
                type = AnalysisType.GENERAL,
                rawDescription = "Blocked: ${safety.reason}"
            )
        }
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return@withContext VisionResult(
                type = AnalysisType.GENERAL,
                rawDescription = "Could not decode image at: $path"
            )
        analyse(bitmap)
    }

    /** Analyse from raw bytes. */
    suspend fun analyseBytes(bytes: ByteArray): VisionResult = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@withContext VisionResult(type = AnalysisType.GENERAL, rawDescription = "Cannot decode image bytes")
        analyse(bitmap)
    }

    // ── OCR ───────────────────────────────────────────────────────────────────

    /** Extract all text from an image using ML Kit. */
    suspend fun performOCR(bitmap: Bitmap): VisionResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text
                    val confidence = if (text.isNotEmpty()) 0.9f else 0f
                    val blocks = result.textBlocks.size
                    Log.d(TAG, "OCR: $blocks blocks, ${text.length} chars")
                    cont.resume(
                        VisionResult(
                            type = AnalysisType.OCR,
                            text = text,
                            confidence = confidence,
                            rawDescription = "OCR extracted ${text.length} characters in $blocks text blocks"
                        )
                    )
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }
    }

    // ── Object detection ──────────────────────────────────────────────────────

    /** Detect objects in an image using ML Kit. */
    suspend fun detectObjects(bitmap: Bitmap): List<DetectedObject> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            objectDetector.process(image)
                .addOnSuccessListener { detected ->
                    val objects = detected.map { obj ->
                        val label = obj.labels.maxByOrNull { it.confidence }
                        DetectedObject(
                            label = label?.text ?: "unknown",
                            confidence = label?.confidence ?: 0f,
                            boundingBox = obj.boundingBox?.let {
                                BoundingBox(it.left, it.top, it.right, it.bottom)
                            }
                        )
                    }
                    Log.d(TAG, "Detected ${objects.size} objects")
                    cont.resume(objects)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Object detection failed: ${e.message}")
                    cont.resume(emptyList())
                }
        }
    }

    // ── Screenshot analysis ───────────────────────────────────────────────────

    /** Analyse a screenshot — combines OCR + structural understanding. */
    suspend fun analyseScreenshot(bitmap: Bitmap): VisionResult {
        val ocr = performOCR(bitmap)
        val objects = detectObjects(bitmap)

        // UI element heuristics
        val hasButtons = ocr.text.contains(Regex("\\b(OK|Cancel|Submit|Save|Delete|Back)\\b", RegexOption.IGNORE_CASE))
        val hasForm = ocr.text.contains(Regex("\\b(Email|Password|Username|Name)\\b", RegexOption.IGNORE_CASE))
        val isLoginScreen = ocr.text.contains(Regex("\\b(Login|Sign In|Sign Up)\\b", RegexOption.IGNORE_CASE))

        val description = buildString {
            appendLine("Screenshot Analysis:")
            if (ocr.text.isNotBlank()) appendLine("Text content: ${ocr.text.take(300)}")
            if (hasButtons) appendLine("UI: Contains interactive buttons")
            if (hasForm) appendLine("UI: Contains form/input fields")
            if (isLoginScreen) appendLine("UI: Appears to be a login/auth screen")
            if (objects.isNotEmpty()) appendLine("Objects: ${objects.joinToString { "${it.label} (${(it.confidence * 100).toInt()}%)" }}")
        }

        return VisionResult(
            type = AnalysisType.SCREENSHOT,
            text = ocr.text,
            objects = objects,
            confidence = ocr.confidence,
            rawDescription = description
        )
    }

    // ── Document analysis ─────────────────────────────────────────────────────

    /** Analyse a document image — extract text + classify document type. */
    suspend fun analyseDocument(bitmap: Bitmap): VisionResult {
        val ocr = performOCR(bitmap)
        val docType = classifyDocument(ocr.text)
        val description = "Document type: $docType\nExtracted text:\n${ocr.text.take(1000)}"

        return VisionResult(
            type = AnalysisType.DOCUMENT,
            text = ocr.text,
            documentType = docType,
            confidence = ocr.confidence,
            rawDescription = description
        )
    }

    private fun classifyDocument(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("invoice") || lower.contains("amount due")    -> "Invoice"
            lower.contains("receipt")                                     -> "Receipt"
            lower.contains("contract") || lower.contains("agreement")    -> "Contract"
            lower.contains("certificate")                                 -> "Certificate"
            lower.contains("resume") || lower.contains("curriculum")     -> "Resume/CV"
            lower.contains("passport") || lower.contains("date of birth")-> "Identity Document"
            lower.contains("report")                                      -> "Report"
            text.matches(Regex(".*[0-9]{4}-[0-9]{2}-[0-9]{2}.*"))       -> "Dated Document"
            else                                                           -> "General Document"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildDescription(ocrText: String, objects: List<DetectedObject>): String = buildString {
        if (ocrText.isNotBlank()) appendLine("Text: ${ocrText.take(300)}")
        if (objects.isNotEmpty()) {
            appendLine("Objects detected:")
            objects.forEach { obj ->
                appendLine("  - ${obj.label} (${(obj.confidence * 100).toInt()}% confidence)")
            }
        }
        if (ocrText.isBlank() && objects.isEmpty()) appendLine("No significant content detected")
    }

    private fun rememberResult(result: VisionResult) {
        if (result.rawDescription.isNotBlank()) {
            memoryAgent.save(
                content = "Vision analysis [${result.type}]: ${result.rawDescription.take(300)}",
                type = MemoryAgent.MemoryType.EPISODIC,
                tags = listOf("vision", result.type.name.lowercase()),
                importance = 0.5f
            )
        }
    }

    // ── Message handler ───────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        if (msg.type != AgentCommunication.MessageType.TASK_REQUEST) return
        val imagePath = msg.payload["imagePath"]?.toString()
        val analysisType = msg.payload["type"]?.toString() ?: "general"

        val result = if (imagePath != null) {
            analyseFile(imagePath)
        } else {
            VisionResult(type = AnalysisType.GENERAL, rawDescription = "No image path provided")
        }

        AgentCommunication.reportResult(
            sender = AgentCommunication.AgentType.VISION,
            receiver = msg.sender,
            taskId = msg.taskId,
            result = mapOf(
                "description" to result.rawDescription,
                "text" to result.text,
                "type" to result.type.name,
                "confidence" to result.confidence,
                "objectCount" to result.objects.size
            )
        )
    }

    fun release() {
        textRecognizer.close()
        objectDetector.close()
    }
}
