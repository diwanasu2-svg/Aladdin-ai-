package com.aladdin.vision.document

import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "DocumentScannerService"

/**
 * DocumentScannerService uses ML Kit Document Scanner (powered by Google Play Services).
 *
 * Features:
 * - Automatic document edge detection
 * - Perspective correction (de-skewing)
 * - Image enhancement (contrast, brightness normalization)
 * - Multi-page document support
 * - PDF export support
 *
 * Usage:
 * 1. Register an ActivityResultLauncher<IntentSender> in your Fragment/Activity.
 * 2. Call [startScan] with the launcher.
 * 3. In the result callback, call [handleScanResult] with the returned ActivityResult.
 */
class DocumentScannerService(private val context: Context) {

    private val scanner: GmsDocumentScanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(10)
                .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
                .build()
        )
    }

    /**
     * Launch the full ML Kit Document Scanner UI.
     * Wire the ActivityResult to [handleScanResult].
     */
    suspend fun startScan(
        launcher: ActivityResultLauncher<IntentSender>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        scanner.getStartScanIntent(context as android.app.Activity)
            .addOnSuccessListener { intentSender ->
                launcher.launch(intentSender)
                cont.resume(Unit)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start scanner", e)
                cont.resumeWithException(e)
            }
    }

    /**
     * Extract the result from the ActivityResult data returned by the scanner UI.
     * Call this from your ActivityResult callback.
     */
    fun handleScanResult(data: android.content.Intent?): DocumentScanResult {
        if (data == null) return DocumentScanResult.error("No scan data received")
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
            ?: return DocumentScanResult.error("No scan data received")

        val pages = result.pages.orEmpty().mapNotNull { page ->
            page?.let {
                ScannedPage(
                    imageUri = it.imageUri,
                    width = 0,
                    height = 0
                )
            }
        }

        val pdfUri = result.pdf?.uri

        Log.d(TAG, "Scan result: ${pages.size} page(s), PDF: $pdfUri")
        return DocumentScanResult.success(pages, pdfUri)
    }

    /**
     * Process a raw bitmap as a document image:
     * applies edge enhancement and contrast normalization.
     * This does NOT perform perspective correction (use the full scanner UI for that).
     */
    suspend fun processDocumentBitmap(bitmap: Bitmap): DocumentScanResult =
        withContext(Dispatchers.IO) {
            try {
                val enhanced = enhanceBitmap(bitmap)
                DocumentScanResult.success(
                    pages = listOf(ScannedPage(imageUri = null, width = bitmap.width, height = bitmap.height, bitmap = enhanced)),
                    pdfUri = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Document bitmap processing failed", e)
                DocumentScanResult.error(e.message ?: "Unknown error", e)
            }
        }

    private fun enhanceBitmap(original: Bitmap): Bitmap {
        // Apply a simple enhancement: increase contrast and sharpen
        // For production use, consider OpenCV or a dedicated image processing library
        val mutable = original.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(mutable.width * mutable.height)
        mutable.getPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF).coerceIn(0, 255)
            val g = ((pixel shr 8) and 0xFF).coerceIn(0, 255)
            val b = (pixel and 0xFF).coerceIn(0, 255)
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            // Adaptive threshold-like enhancement
            val enhanced = if (gray > 128) minOf(255, (gray * 1.2).toInt()) else maxOf(0, (gray * 0.8).toInt())
            pixels[i] = (0xFF shl 24) or (enhanced shl 16) or (enhanced shl 8) or enhanced
        }

        mutable.setPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        return mutable
    }
}
