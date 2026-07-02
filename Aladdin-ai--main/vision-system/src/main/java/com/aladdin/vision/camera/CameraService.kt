package com.aladdin.vision.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraService"
private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

/**
 * CameraService wraps CameraX for photo capture, video recording, and flash control.
 *
 * Usage:
 * 1. Call [startPreview] from your Fragment/Activity.
 * 2. [capturePhoto] / [startVideoRecording] / [stopVideoRecording] as needed.
 * 3. Call [release] in onDestroy.
 */
class CameraService(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var useFrontCamera = false
    private var isTorchOn = false

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ─── Preview ──────────────────────────────────────────────────────────────

    fun startPreview(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        useFrontCamera: Boolean = false
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        this.useFrontCamera = useFrontCamera
        bindCamera()
    }

    private fun bindCamera() {
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    owner, selector, preview, imageCapture, videoCapture
                )
                Log.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ─── Photo Capture ────────────────────────────────────────────────────────

    suspend fun capturePhoto(): PhotoResult = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("ImageCapture not initialized"))
            return@suspendCancellableCoroutine
        }

        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Aladdin")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    Log.d(TAG, "Photo saved: $uri")
                    cont.resume(PhotoResult.Success(uri!!, timestamp))
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    // ─── Video Recording ──────────────────────────────────────────────────────

    fun startVideoRecording(onStart: () -> Unit = {}) {
        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture not initialized")
            return
        }

        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Aladdin")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = capture.output.prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Recording started")
                        onStart()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording error: ${event.error}")
                        } else {
                            Log.d(TAG, "Recording saved: ${event.outputResults.outputUri}")
                        }
                    }
                }
            }
    }

    suspend fun stopVideoRecording(): VideoResult = suspendCancellableCoroutine { cont ->
        val recording = activeRecording ?: run {
            cont.resumeWithException(IllegalStateException("No active recording"))
            return@suspendCancellableCoroutine
        }
        recording.stop()
        activeRecording = null

        // The Finalize event carries the URI — re-bind a one-shot listener
        videoCapture?.output?.prepareRecording(
            context,
            MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).build()
        )
        // URI is delivered via the event stream; simplest approach: return success signal
        cont.resume(VideoResult.Stopped)
    }

    // ─── Flash ────────────────────────────────────────────────────────────────

    fun setFlashMode(mode: FlashMode) {
        imageCapture?.flashMode = when (mode) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.TORCH -> {
                camera?.cameraControl?.enableTorch(true)
                isTorchOn = true
                return
            }
        }
    }

    fun toggleTorch(): Boolean {
        isTorchOn = !isTorchOn
        camera?.cameraControl?.enableTorch(isTorchOn)
        return isTorchOn
    }

    // ─── Camera flip ──────────────────────────────────────────────────────────

    fun flipCamera() {
        useFrontCamera = !useFrontCamera
        bindCamera()
    }

    // ─── Image Analysis callback (used by barcode live scan) ─────────────────

    private var imageAnalyzer: ImageAnalysis? = null

    fun setImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, analyzer) }
        rebindWithAnalyzer()
    }

    fun clearImageAnalyzer() {
        imageAnalyzer = null
        bindCamera()
    }

    private fun rebindWithAnalyzer() {
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider?.unbindAll()
            val useCases = listOfNotNull(preview, imageCapture, videoCapture, imageAnalyzer)
            camera = cameraProvider?.bindToLifecycle(owner, selector, *useCases.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Rebind with analyzer failed", e)
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    fun release() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
