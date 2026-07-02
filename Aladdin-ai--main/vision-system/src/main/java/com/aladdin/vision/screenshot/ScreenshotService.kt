package com.aladdin.vision.screenshot

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
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "ScreenshotService"
private const val VIRTUAL_DISPLAY_NAME = "AladdinScreenCapture"

/**
 * ScreenshotService uses MediaProjection API to capture the device screen.
 *
 * Flow:
 * 1. From your Activity, launch: startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
 * 2. In onActivityResult, pass resultCode + data to [captureScreen].
 */
class ScreenshotService(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    suspend fun captureScreen(resultCode: Int, data: Intent): ScreenshotResult =
        withContext(Dispatchers.IO) {
            if (resultCode != Activity.RESULT_OK) {
                return@withContext ScreenshotResult.error("User denied screen capture permission")
            }

            val metrics = getDisplayMetrics()
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            suspendCancellableCoroutine { cont ->
                try {
                    val projectionManager =
                        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data)

                    imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader?.surface,
                        null, null
                    )

                    Handler(Looper.getMainLooper()).postDelayed({
                        val image = imageReader?.acquireLatestImage()
                        if (image == null) {
                            cleanup()
                            cont.resume(ScreenshotResult.error("Failed to acquire image from screen"))
                            return@postDelayed
                        }

                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)

                            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            bitmap.recycle()
                            image.close()

                            Log.d(TAG, "Screenshot captured: ${width}x${height}")
                            cont.resume(ScreenshotResult.success(croppedBitmap, width, height))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process screenshot", e)
                            image.close()
                            cont.resume(ScreenshotResult.error("Failed to process image: ${e.message}", e))
                        } finally {
                            cleanup()
                        }
                    }, 500) // Small delay to let the screen render
                } catch (e: Exception) {
                    Log.e(TAG, "Screen capture failed", e)
                    cleanup()
                    cont.resume(ScreenshotResult.error("Screen capture failed: ${e.message}", e))
                }
            }
        }

    private fun getDisplayMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
