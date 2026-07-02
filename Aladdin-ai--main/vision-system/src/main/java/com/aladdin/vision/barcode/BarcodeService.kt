package com.aladdin.vision.barcode

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aladdin.vision.camera.CameraService
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BarcodeService"

/**
 * BarcodeService uses ML Kit Barcode Scanning to detect and decode:
 * - QR codes
 * - EAN-13, EAN-8, UPC-A, UPC-E
 * - Code 39, Code 93, Code 128
 * - Data Matrix, PDF417, Aztec
 *
 * Extracted structured data types:
 * - URLs
 * - Contact cards (vCard)
 * - Calendar events
 * - WiFi credentials
 * - Phone numbers / emails / SMS
 * - Geographic coordinates
 */
class BarcodeService(private val context: android.content.Context) {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }

    private var isLiveScanningActive = false

    // ─── Single image scan ────────────────────────────────────────────────────

    suspend fun scanBarcodes(bitmap: Bitmap): List<BarcodeResult> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    Log.d(TAG, "Found ${barcodes.size} barcode(s)")
                    cont.resume(barcodes.map { BarcodeResult.from(it) })
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scan failed", e)
                    cont.resumeWithException(e)
                }
        }

    // ─── Live camera scanning ─────────────────────────────────────────────────

    fun startLiveScanning(
        cameraService: CameraService,
        onBarcodesDetected: (List<BarcodeResult>) -> Unit
    ) {
        if (isLiveScanningActive) return
        isLiveScanningActive = true

        cameraService.setImageAnalyzer(object : ImageAnalysis.Analyzer {
            @androidx.camera.core.ExperimentalGetImage
            override fun analyze(imageProxy: ImageProxy) {
                val mediaImage = imageProxy.image ?: run {
                    imageProxy.close()
                    return
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            onBarcodesDetected(barcodes.map { BarcodeResult.from(it) })
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }
        })
    }

    fun stopLiveScanning(cameraService: CameraService? = null) {
        isLiveScanningActive = false
        cameraService?.clearImageAnalyzer()
    }

    fun close() {
        scanner.close()
    }
}
