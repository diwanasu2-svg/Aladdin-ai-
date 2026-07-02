package com.aladdin.vision.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "ObjectDetectionService"

/**
 * ObjectDetectionService uses ML Kit Object Detection to find and classify objects in images.
 *
 * Features:
 * - Detects multiple objects per image (up to 5 with SINGLE_IMAGE_MODE)
 * - Returns bounding boxes for each detected object
 * - Provides classification labels with confidence scores
 * - Supports both single-image and streaming (live camera) modes
 */
class ObjectDetectionService(private val context: Context) {

    private var detector: ObjectDetector? = null

    private fun getDetector(
        enableMultipleObjects: Boolean,
        enableClassification: Boolean
    ): ObjectDetector {
        return detector ?: ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .apply {
                    if (enableMultipleObjects) enableMultipleObjects()
                    if (enableClassification) enableClassification()
                }
                .build()
        ).also { detector = it }
    }

    suspend fun detect(
        bitmap: Bitmap,
        enableMultipleObjects: Boolean = true,
        enableClassification: Boolean = true
    ): List<DetectedObject> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val detector = getDetector(enableMultipleObjects, enableClassification)

        detector.process(image)
            .addOnSuccessListener { detectedObjects ->
                Log.d(TAG, "Detected ${detectedObjects.size} object(s)")
                val results = detectedObjects.map { obj ->
                    DetectedObject(
                        boundingBox = obj.boundingBox,
                        trackingId = obj.trackingId,
                        labels = obj.labels.map { label ->
                            ObjectLabel(
                                text = label.text,
                                confidence = label.confidence,
                                index = label.index
                            )
                        }
                    )
                }
                cont.resume(results)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Object detection failed", e)
                cont.resumeWithException(e)
            }
    }

    fun close() {
        detector?.close()
        detector = null
    }
}
