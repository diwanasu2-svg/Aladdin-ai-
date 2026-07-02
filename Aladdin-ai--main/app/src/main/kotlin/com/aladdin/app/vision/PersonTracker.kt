package com.aladdin.app.vision

import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

// ─── Phase 7 Item 2: Person Tracking — persistent IDs, IoU-based re-ID ───────

@Singleton
class PersonTracker @Inject constructor() {
    companion object {
        private const val TAG              = "PersonTracker"
        private const val IOU_THRESHOLD    = 0.3f
        private const val MAX_MISSING_FRAMES = 30
        private const val MAX_TRACKS      = 20
    }

    data class TrackedPerson(
        val trackId: Int,
        val bbox: RectF,
        val confidence: Float,
        val framesTracked: Int,
        val framesMissing: Int,
        val velocity: Pair<Float, Float>,
        val attributes: Map<String, String> = emptyMap()
    )

    private val _tracks = MutableStateFlow<List<TrackedPerson>>(emptyList())
    val tracks: StateFlow<List<TrackedPerson>> = _tracks

    private var nextId = 1
    private val activeTracks = mutableMapOf<Int, TrackedPerson>()

    data class Detection(
        val bbox: RectF,
        val confidence: Float,
        val attributes: Map<String, String> = emptyMap()
    )

    // ── Update tracker with new frame detections ──────────────────────────────
    fun update(detections: List<Detection>) {
        val matched    = mutableSetOf<Int>()
        val usedDets   = mutableSetOf<Int>()
        val updated    = mutableMapOf<Int, TrackedPerson>()

        // Match detections to existing tracks using IoU
        for ((trackId, track) in activeTracks) {
            var bestIou = IOU_THRESHOLD
            var bestIdx = -1

            detections.forEachIndexed { i, det ->
                if (i !in usedDets) {
                    val iou = computeIoU(track.bbox, det.bbox)
                    if (iou > bestIou) { bestIou = iou; bestIdx = i }
                }
            }

            if (bestIdx >= 0) {
                val det = detections[bestIdx]
                val vx  = det.bbox.centerX() - track.bbox.centerX()
                val vy  = det.bbox.centerY() - track.bbox.centerY()
                updated[trackId] = track.copy(
                    bbox          = det.bbox,
                    confidence    = det.confidence,
                    framesTracked = track.framesTracked + 1,
                    framesMissing = 0,
                    velocity      = Pair(vx, vy),
                    attributes    = det.attributes.ifEmpty { track.attributes }
                )
                matched.add(trackId)
                usedDets.add(bestIdx)
            } else {
                // Track lost — increment missing counter
                val newMissing = track.framesMissing + 1
                if (newMissing < MAX_MISSING_FRAMES) {
                    // Predict position using velocity
                    val predictedBbox = RectF(
                        track.bbox.left   + track.velocity.first,
                        track.bbox.top    + track.velocity.second,
                        track.bbox.right  + track.velocity.first,
                        track.bbox.bottom + track.velocity.second
                    )
                    updated[trackId] = track.copy(
                        bbox          = predictedBbox,
                        framesMissing = newMissing
                    )
                } else {
                    Log.d(TAG, "Track $trackId lost after $MAX_MISSING_FRAMES frames")
                }
            }
        }

        // New detections → create new tracks
        detections.forEachIndexed { i, det ->
            if (i !in usedDets && activeTracks.size < MAX_TRACKS) {
                val id = nextId++
                updated[id] = TrackedPerson(
                    trackId       = id,
                    bbox          = det.bbox,
                    confidence    = det.confidence,
                    framesTracked = 1,
                    framesMissing = 0,
                    velocity      = Pair(0f, 0f),
                    attributes    = det.attributes
                )
                Log.i(TAG, "New track ID=$id created")
            }
        }

        activeTracks.clear()
        activeTracks.putAll(updated)
        _tracks.value = activeTracks.values.toList()
    }

    // ── Re-identification: match a query bounding box to existing track ───────
    fun reIdentify(query: RectF): TrackedPerson? {
        return activeTracks.values
            .filter { it.framesMissing < MAX_MISSING_FRAMES / 2 }
            .maxByOrNull { computeIoU(it.bbox, query) }
            ?.takeIf { computeIoU(it.bbox, query) > IOU_THRESHOLD }
    }

    // ── IoU calculation ───────────────────────────────────────────────────────
    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left,   b.left)
        val interTop    = maxOf(a.top,    b.top)
        val interRight  = minOf(a.right,  b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interLeft >= interRight || interTop >= interBottom) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea     = (a.right - a.left) * (a.bottom - a.top)
        val bArea     = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }

    // ── Count persons in current frame ────────────────────────────────────────
    fun personCount(): Int = activeTracks.count { it.value.framesMissing == 0 }

    // ── Get track history summary ─────────────────────────────────────────────
    fun getSummary(): String {
        val active = personCount()
        val total  = nextId - 1
        return "Tracking $active person(s) now. $total unique persons seen total."
    }

    fun reset() {
        activeTracks.clear()
        _tracks.value = emptyList()
        nextId = 1
        Log.i(TAG, "Tracker reset")
    }
}
