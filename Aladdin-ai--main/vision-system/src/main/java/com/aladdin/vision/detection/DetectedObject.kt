package com.aladdin.vision.detection

import android.graphics.Rect

data class DetectedObject(
    val boundingBox: Rect,
    val trackingId: Int?,
    val labels: List<ObjectLabel>
) {
    val primaryLabel: ObjectLabel? get() = labels.maxByOrNull { it.confidence }
    val displayName: String get() = primaryLabel?.text ?: "Unknown"
    val confidence: Float get() = primaryLabel?.confidence ?: 0f
}

data class ObjectLabel(
    val text: String,
    val confidence: Float,
    val index: Int
)
