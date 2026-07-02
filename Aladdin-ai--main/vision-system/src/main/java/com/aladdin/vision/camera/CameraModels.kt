package com.aladdin.vision.camera

import android.net.Uri

enum class FlashMode { AUTO, ON, OFF, TORCH }

sealed class PhotoResult {
    data class Success(val uri: Uri, val timestamp: String) : PhotoResult()
    data class Error(val message: String, val cause: Throwable? = null) : PhotoResult()
}

sealed class VideoResult {
    data class Success(val uri: Uri, val durationMs: Long = 0) : VideoResult()
    object Stopped : VideoResult()
    data class Error(val message: String, val cause: Throwable? = null) : VideoResult()
}
