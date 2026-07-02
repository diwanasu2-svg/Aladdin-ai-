package com.aladdin.vision.document

import android.graphics.Bitmap
import android.net.Uri

data class ScannedPage(
    val imageUri: Uri?,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap? = null
)

data class DocumentScanResult(
    val success: Boolean,
    val pages: List<ScannedPage>,
    val pdfUri: Uri?,
    val pageCount: Int,
    val error: String?,
    val cause: Throwable?
) {
    companion object {
        fun success(pages: List<ScannedPage>, pdfUri: Uri?) = DocumentScanResult(
            success = true,
            pages = pages,
            pdfUri = pdfUri,
            pageCount = pages.size,
            error = null,
            cause = null
        )

        fun error(message: String, cause: Throwable? = null) = DocumentScanResult(
            success = false,
            pages = emptyList(),
            pdfUri = null,
            pageCount = 0,
            error = message,
            cause = cause
        )
    }
}
