package com.aladdin.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.aladdin.vision.accessibility.AladdinAccessibilityService
import com.aladdin.vision.accessibility.ScreenContent
import com.aladdin.vision.barcode.BarcodeResult
import com.aladdin.vision.barcode.BarcodeService
import com.aladdin.vision.camera.CameraService
import com.aladdin.vision.camera.FlashMode
import com.aladdin.vision.camera.PhotoResult
import com.aladdin.vision.camera.VideoResult
import com.aladdin.vision.detection.DetectedObject
import com.aladdin.vision.detection.ObjectDetectionService
import com.aladdin.vision.document.DocumentScanResult
import com.aladdin.vision.document.DocumentScannerService
import com.aladdin.vision.ocr.OCRResult
import com.aladdin.vision.ocr.OCRService
import com.aladdin.vision.ocr.RecognitionLanguage
import com.aladdin.vision.screenshot.ScreenshotResult
import com.aladdin.vision.screenshot.ScreenshotService
import com.aladdin.vision.understanding.GeminiVisionService
import com.aladdin.vision.understanding.VisionUnderstandingResult

/**
 * VisionModule — Central entry point for the Aladdin Complete Vision System.
 *
 * Provides a unified API over:
 *  - CameraX (photo + video + flash)
 *  - ML Kit OCR (printed + handwritten, multi-language)
 *  - Gemini Vision (captioning, object recognition, scene understanding)
 *  - MediaProjection (screenshot + screen analysis)
 *  - ML Kit Object Detection (bounding boxes + classification)
 *  - ML Kit Document Scanner (detection, perspective correction, enhancement)
 *  - ML Kit Barcode Scanner (QR, URL, contact, calendar)
 *  - Accessibility API (screen content extraction, UI element detection)
 */
class VisionModule private constructor(private val context: Context) {

    // ─── Services ─────────────────────────────────────────────────────────────

    private val cameraService: CameraService by lazy { CameraService(context) }
    private val ocrService: OCRService by lazy { OCRService(context) }
    private val geminiService: GeminiVisionService by lazy { GeminiVisionService(context) }
    private val screenshotService: ScreenshotService by lazy { ScreenshotService(context) }
    private val objectDetectionService: ObjectDetectionService by lazy { ObjectDetectionService(context) }
    private val documentScannerService: DocumentScannerService by lazy { DocumentScannerService(context) }
    private val barcodeService: BarcodeService by lazy { BarcodeService(context) }

    // ─── Gemini API Key configuration ─────────────────────────────────────────

    fun configureGemini(apiKey: String) {
        geminiService.configure(apiKey)
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    /**
     * Binds the camera preview to the given [PreviewView].
     * Call this from your Fragment/Activity once the view is ready.
     */
    fun startCameraPreview(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        useFrontCamera: Boolean = false
    ) = cameraService.startPreview(previewView, lifecycleOwner, useFrontCamera)

    /** Capture a still photo. Returns [PhotoResult] with the saved [Uri]. */
    suspend fun capturePhoto(): PhotoResult = cameraService.capturePhoto()

    /** Start video recording. Resolves immediately; call [stopVideoRecording] to finish. */
    fun startVideoRecording(onStart: () -> Unit = {}) = cameraService.startVideoRecording(onStart)

    /** Stop an in-progress video recording. Returns [VideoResult] with the saved [Uri]. */
    suspend fun stopVideoRecording(): VideoResult = cameraService.stopVideoRecording()

    /** Set flash mode. [FlashMode.AUTO], [FlashMode.ON], [FlashMode.OFF], or [FlashMode.TORCH]. */
    fun setFlashMode(mode: FlashMode) = cameraService.setFlashMode(mode)

    /** Toggle the torch (continuous flash). Returns true if torch is now ON. */
    fun toggleTorch(): Boolean = cameraService.toggleTorch()

    /** Switch between front and rear cameras. */
    fun flipCamera() = cameraService.flipCamera()

    /** Release all camera resources. Call from onDestroy. */
    fun releaseCamera() = cameraService.release()

    // ─── OCR ──────────────────────────────────────────────────────────────────

    /**
     * Run OCR (text recognition) on a [Bitmap].
     *
     * @param language Which language model to use. Defaults to Latin (covers most Western scripts).
     *                 Also supports CHINESE, DEVANAGARI, JAPANESE, KOREAN for non-Latin scripts.
     * @param includeHandwritten Whether to use the model variant that handles handwriting.
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        language: RecognitionLanguage = RecognitionLanguage.LATIN,
        includeHandwritten: Boolean = false
    ): OCRResult = ocrService.recognizeText(bitmap, language, includeHandwritten)

    /** Convenience: run OCR on a [Uri] (file or content URI). */
    suspend fun recognizeTextFromUri(
        uri: Uri,
        language: RecognitionLanguage = RecognitionLanguage.LATIN,
        includeHandwritten: Boolean = false
    ): OCRResult = ocrService.recognizeTextFromUri(uri, language, includeHandwritten)

    // ─── Gemini Vision ────────────────────────────────────────────────────────

    /** Generate a natural-language caption for the given image. */
    suspend fun captionImage(bitmap: Bitmap): VisionUnderstandingResult =
        geminiService.captionImage(bitmap)

    /** Identify and list objects visible in the image. */
    suspend fun recognizeObjects(bitmap: Bitmap): VisionUnderstandingResult =
        geminiService.recognizeObjects(bitmap)

    /** Describe the overall scene, setting, and context of the image. */
    suspend fun understandScene(bitmap: Bitmap): VisionUnderstandingResult =
        geminiService.understandScene(bitmap)

    /** Ask Gemini Vision an arbitrary question about the image. */
    suspend fun askAboutImage(bitmap: Bitmap, question: String): VisionUnderstandingResult =
        geminiService.askAboutImage(bitmap, question)

    // ─── Screenshot ───────────────────────────────────────────────────────────

    /**
     * Capture the current screen via [android.media.projection.MediaProjection].
     * Requires the user to have already granted media projection permission — pass the
     * resultCode + data from [Activity.onActivityResult] for ACTION_MEDIA_PROJECTION.
     */
    suspend fun captureScreen(resultCode: Int, data: android.content.Intent): ScreenshotResult =
        screenshotService.captureScreen(resultCode, data)

    /**
     * Capture the screen and then run Gemini Vision analysis on it in one call.
     */
    suspend fun captureAndAnalyzeScreen(
        resultCode: Int,
        data: android.content.Intent
    ): Pair<ScreenshotResult, VisionUnderstandingResult> {
        val screenshot = screenshotService.captureScreen(resultCode, data)
        val analysis = screenshot.bitmap?.let { geminiService.understandScene(it) }
            ?: VisionUnderstandingResult.error("Screenshot bitmap is null")
        return screenshot to analysis
    }

    // ─── Object Detection ─────────────────────────────────────────────────────

    /**
     * Detect objects in a [Bitmap]. Returns a list of [DetectedObject] with
     * bounding boxes and classification labels.
     */
    suspend fun detectObjects(
        bitmap: Bitmap,
        enableMultipleObjects: Boolean = true,
        enableClassification: Boolean = true
    ): List<DetectedObject> =
        objectDetectionService.detect(bitmap, enableMultipleObjects, enableClassification)

    // ─── Document Scanning ────────────────────────────────────────────────────

    /**
     * Scan a document from the camera. Launches the ML Kit Document Scanner UI and
     * returns the corrected, enhanced image(s) via [DocumentScanResult].
     */
    suspend fun scanDocument(
        activityResultLauncher: androidx.activity.result.ActivityResultLauncher<android.content.IntentSender>
    ) = documentScannerService.startScan(activityResultLauncher)

    /**
     * Process a raw bitmap as a document: detect edges, apply perspective correction
     * and enhancement without launching any scanner UI.
     */
    suspend fun processDocumentBitmap(bitmap: Bitmap): DocumentScanResult =
        documentScannerService.processDocumentBitmap(bitmap)

    // ─── Barcode / QR ─────────────────────────────────────────────────────────

    /**
     * Scan barcodes and QR codes in a [Bitmap].
     * Returns a list of [BarcodeResult] — each contains the raw value plus decoded
     * structured data (URL, contact card, calendar event, etc.).
     */
    suspend fun scanBarcodes(bitmap: Bitmap): List<BarcodeResult> =
        barcodeService.scanBarcodes(bitmap)

    /** Convenience: live barcode scanning on the camera preview stream. */
    fun startLiveBarcodeScanning(onBarcodesDetected: (List<BarcodeResult>) -> Unit) =
        barcodeService.startLiveScanning(cameraService, onBarcodesDetected)

    fun stopLiveBarcodeScanning() = barcodeService.stopLiveScanning()

    // ─── Screen Understanding (Accessibility) ─────────────────────────────────

    /**
     * Retrieve the current screen content using the Accessibility API.
     * [AladdinAccessibilityService] must be enabled in Android Settings for this to work.
     */
    fun getScreenContent(): ScreenContent? =
        AladdinAccessibilityService.instance?.getScreenContent()

    /** Check whether the accessibility service is currently active. */
    fun isAccessibilityServiceEnabled(): Boolean =
        AladdinAccessibilityService.instance != null

    /** Find all UI elements on screen matching a given text (case-insensitive). */
    fun findElementsByText(text: String) =
        AladdinAccessibilityService.instance?.findNodesByText(text) ?: emptyList()

    /** Find a UI element by its resource ID. */
    fun findElementById(viewId: String) =
        AladdinAccessibilityService.instance?.findNodeById(viewId)

    // ─── Companion (Singleton factory) ────────────────────────────────────────

    companion object {
        @Volatile private var instance: VisionModule? = null

        fun getInstance(context: Context): VisionModule =
            instance ?: synchronized(this) {
                instance ?: VisionModule(context.applicationContext).also { instance = it }
            }
    }
}
