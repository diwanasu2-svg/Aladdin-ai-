package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 7 Item 5: CameraX Live Analysis ────────────────────────────────────

@Singleton
class CameraXLiveAnalysis @Inject constructor(
    @ApplicationContext private val context: Context,
    private val visionEngine: VisionEngine
) {
    companion object {
        private const val TAG            = "CameraXLive"
        private const val ANALYSIS_FPS   = 1   // analyze 1 frame/sec
        private const val COOLDOWN_MS    = 2000L
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scope            = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _liveResult   = MutableStateFlow<LiveAnalysisResult?>(null)
    val liveResult: StateFlow<LiveAnalysisResult?> = _liveResult

    private val _isRunning    = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private var lastAnalysisMs = 0L
    private var apiKey         = ""
    private var cameraProvider: ProcessCameraProvider? = null

    data class LiveAnalysisResult(
        val description: String,
        val detectedObjects: List<String>,
        val extractedText: String,
        val frameTimestampMs: Long,
        val analysisMode: AnalysisMode
    )

    enum class AnalysisMode { DESCRIPTION, OCR, OBJECT_DETECTION, SCENE }

    var analysisMode: AnalysisMode = AnalysisMode.DESCRIPTION

    // ── Start camera + live analysis ──────────────────────────────────────────
    fun startLiveAnalysis(
        lifecycleOwner: LifecycleOwner,
        apiKey: String,
        previewSurface: Preview.SurfaceProvider? = null,
        mode: AnalysisMode = AnalysisMode.DESCRIPTION
    ) {
        this.apiKey       = apiKey
        this.analysisMode = mode
        _isRunning.value  = true
        Log.i(TAG, "Starting CameraX live analysis in $mode mode")

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()

                val preview = Preview.Builder().build()
                previewSurface?.let { preview.setSurfaceProvider(it) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                    analyzeFrame(proxy)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider!!.unbindAll()
                cameraProvider!!.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                Log.i(TAG, "CameraX bound successfully")

            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed: ${e.message}")
                _isRunning.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── Analyze individual frames ─────────────────────────────────────────────
    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < COOLDOWN_MS) {
                proxy.close()
                return
            }
            lastAnalysisMs = now

            val bitmap = proxy.toBitmap() ?: run { proxy.close(); return }
            proxy.close()

            scope.launch {
                try {
                    val prompt = when (analysisMode) {
                        AnalysisMode.DESCRIPTION      -> "Describe this scene in 1-2 sentences."
                        AnalysisMode.OCR              -> "Extract all text visible in this image."
                        AnalysisMode.OBJECT_DETECTION -> "List all objects you can see in this image, comma-separated."
                        AnalysisMode.SCENE            -> "Classify this scene: indoor/outdoor, location type, time of day."
                    }

                    val result = visionEngine.analyzeImage(bitmap, prompt, apiKey)
                    val objects = if (analysisMode == AnalysisMode.OBJECT_DETECTION) {
                        result.description.split(",").map { it.trim() }
                    } else emptyList()

                    _liveResult.value = LiveAnalysisResult(
                        description      = result.description,
                        detectedObjects  = objects,
                        extractedText    = if (analysisMode == AnalysisMode.OCR) result.description else "",
                        frameTimestampMs = now,
                        analysisMode     = analysisMode
                    )
                    Log.d(TAG, "Frame analyzed: ${result.description.take(80)}…")

                } catch (e: Exception) {
                    Log.e(TAG, "Frame analysis error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeFrame error: ${e.message}")
            proxy.close()
        }
    }

    // ── Capture single frame ──────────────────────────────────────────────────
    fun captureAndAnalyze(
        lifecycleOwner: LifecycleOwner,
        apiKey: String,
        prompt: String,
        onResult: (String) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider  = future.get()
                val capture   = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector  = CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(lifecycleOwner, selector, capture)

                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bmp = image.toBitmap()
                            image.close()
                            if (bmp != null) {
                                scope.launch {
                                    val res = visionEngine.analyzeImage(bmp, prompt, apiKey)
                                    withContext(Dispatchers.Main) { onResult(res.description) }
                                }
                            } else {
                                onResult("Failed to capture image.")
                            }
                        }
                        override fun onError(exc: ImageCaptureException) {
                            onResult("Capture error: ${exc.message}")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "captureAndAnalyze error: ${e.message}")
                onResult("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopLiveAnalysis() {
        try {
            cameraProvider?.unbindAll()
            _isRunning.value = false
            Log.i(TAG, "CameraX stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stop error: ${e.message}")
        }
    }

    fun release() {
        stopLiveAnalysis()
        scope.cancel()
        analysisExecutor.shutdown()
    }

    // Extension: convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val ySize   = yBuffer.remaining()
            val uSize   = uBuffer.remaining()
            val vSize   = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val bytes = out.toByteArray()
            val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            // Rotate to correct orientation
            val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "toBitmap error: ${e.message}")
            null
        }
    }
}
