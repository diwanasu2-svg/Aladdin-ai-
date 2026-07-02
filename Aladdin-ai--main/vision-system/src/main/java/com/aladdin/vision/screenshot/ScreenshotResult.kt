package com.aladdin.vision.screenshot

import android.graphics.Bitmap

data class ScreenshotResult(
    val success: Boolean,
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
    val error: String?,
    val cause: Throwable?
) {
    companion object {
        fun success(bitmap: Bitmap, width: Int, height: Int) = ScreenshotResult(
            success = true,
            bitmap = bitmap,
            width = width,
            height = height,
            error = null,
            cause = null
        )

        fun error(message: String, cause: Throwable? = null) = ScreenshotResult(
            success = false,
            bitmap = null,
            width = 0,
            height = 0,
            error = message,
            cause = cause
        )
    }
}
