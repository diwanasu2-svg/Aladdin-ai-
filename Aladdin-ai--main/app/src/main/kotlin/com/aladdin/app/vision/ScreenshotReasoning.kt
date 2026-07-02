package com.aladdin.app.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 7 Item 6: Screenshot Reasoning — capture + analyze screen content ──

@Singleton
class ScreenshotReasoning @Inject constructor(
    @ApplicationContext private val context: Context,
    private val visionEngine: VisionEngine
) {
    companion object {
        private const val TAG         = "ScreenshotReasoning"
        const val REQUEST_CODE_CAPTURE = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null

    data class ScreenAnalysisResult(
        val rawDescription: String,
        val detectedApp: String,
        val visibleText: String,
        val suggestedAction: String,
        val timestampMs: Long
    )

    // ── Capture from root view (no permission required for own app) ───────────
    suspend fun captureAppScreen(activity: Activity): Bitmap? = withContext(Dispatchers.Main) {
        return@withContext try {
            val rootView = activity.window.decorView.rootView
            rootView.isDrawingCacheEnabled = true
            val bmp = Bitmap.createBitmap(rootView.drawingCache)
            rootView.isDrawingCacheEnabled = false
            Log.i(TAG, "App screen captured: ${bmp.width}x${bmp.height}")
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "captureAppScreen failed: ${e.message}")
            null
        }
    }

    // ── Analyze screenshot with Gemini ────────────────────────────────────────
    suspend fun analyzeScreenshot(
        bitmap: Bitmap,
        apiKey: String,
        analysisType: AnalysisType = AnalysisType.GENERAL
    ): ScreenAnalysisResult = withContext(Dispatchers.IO) {
        val prompt = when (analysisType) {
            AnalysisType.GENERAL         -> "Describe what you see on this screen. What app is open? What is the user doing?"
            AnalysisType.TEXT_EXTRACTION -> "Extract all text visible on this screen, preserving the layout."
            AnalysisType.UI_UNDERSTANDING -> "Describe the UI elements on screen: buttons, text fields, lists. What actions can the user take?"
            AnalysisType.CONTENT_SUMMARY -> "Summarize the main content displayed on this screen in 2-3 sentences."
            AnalysisType.ERROR_DETECTION -> "Is there an error, warning, or problem visible on this screen? Describe it."
            AnalysisType.ACTION_SUGGESTION -> "Based on what's on screen, what should the user do next?"
        }

        try {
            val result = visionEngine.analyzeImage(bitmap, prompt, apiKey)

            // Try to identify the app from UI elements
            val appName = detectAppFromContent(result.description)
            val visibleText = if (analysisType == AnalysisType.TEXT_EXTRACTION) result.description else ""
            val suggestion = if (analysisType == AnalysisType.ACTION_SUGGESTION) result.description else ""

            Log.i(TAG, "Screenshot analyzed: ${result.description.take(100)}")
            ScreenAnalysisResult(
                rawDescription  = result.description,
                detectedApp     = appName,
                visibleText     = visibleText,
                suggestedAction = suggestion,
                timestampMs     = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "analyzeScreenshot failed: ${e.message}")
            ScreenAnalysisResult("Analysis failed: ${e.message}", "", "", "", System.currentTimeMillis())
        }
    }

    // ── Screen content understanding pipeline ─────────────────────────────────
    suspend fun understandScreenContext(
        bitmap: Bitmap,
        apiKey: String,
        userQuestion: String = ""
    ): String = withContext(Dispatchers.IO) {
        val basePrompt = if (userQuestion.isNotBlank())
            "Looking at this screen, answer: $userQuestion"
        else
            "What is the most important thing happening on this screen and what might the user need help with?"

        try {
            val result = visionEngine.analyzeImage(bitmap, basePrompt, apiKey)
            result.description
        } catch (e: Exception) {
            "Could not analyze screen: ${e.message}"
        }
    }

    // ── MediaProjection setup (requires user permission + Activity result) ────
    fun getProjectionIntent(): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun initMediaProjection(resultCode: Int, data: Intent) {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        Log.i(TAG, "MediaProjection initialized")
    }

    suspend fun captureViaProjection(width: Int = 1080, height: Int = 1920): Bitmap? = withContext(Dispatchers.IO) {
        val mp = mediaProjection ?: return@withContext null
        return@withContext try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "AladdinCapture", width, height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            Thread.sleep(300)  // Wait for frame
            val image = imageReader!!.acquireLatestImage() ?: return@withContext null
            val plane  = image.planes[0]
            val buffer = plane.buffer
            val pixelStride   = plane.pixelStride
            val rowStride     = plane.rowStride
            val rowPadding    = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            releaseProjection()
            Bitmap.createBitmap(bmp, 0, 0, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "captureViaProjection failed: ${e.message}")
            releaseProjection()
            null
        }
    }

    fun releaseProjection() {
        try { virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null; mediaProjection = null
    }

    private fun detectAppFromContent(description: String): String {
        val lower = description.lowercase()
        return when {
            "youtube" in lower || "video" in lower && "channel" in lower -> "YouTube"
            "gmail" in lower || "inbox" in lower || "email" in lower     -> "Gmail"
            "chrome" in lower || "browser" in lower || "url" in lower    -> "Chrome"
            "maps" in lower || "navigation" in lower || "route" in lower -> "Maps"
            "settings" in lower && "android" in lower                    -> "Settings"
            "camera" in lower && "shutter" in lower                      -> "Camera"
            "whatsapp" in lower || "message" in lower && "chat" in lower -> "Messages"
            "spotify" in lower || "music" in lower && "player" in lower  -> "Music"
            else                                                          -> "Unknown App"
        }
    }

    enum class AnalysisType {
        GENERAL, TEXT_EXTRACTION, UI_UNDERSTANDING, CONTENT_SUMMARY, ERROR_DETECTION, ACTION_SUGGESTION
    }
}
