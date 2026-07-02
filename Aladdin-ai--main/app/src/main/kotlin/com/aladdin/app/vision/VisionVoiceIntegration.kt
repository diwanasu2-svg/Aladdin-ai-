package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── Phase 7 Item 9: Vision + Voice Integration — cross-modal pipeline ────────

@Singleton
class VisionVoiceIntegration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val visionEngine: VisionEngine,
    private val objectMemory: ObjectMemory
) {
    companion object { private const val TAG = "VisionVoice" }

    data class VisionVoiceResponse(
        val spokenText: String,
        val visualDescription: String,
        val detectedObjects: List<String>,
        val memoryKey: String?,
        val confidence: Float
    )

    // ── Route voice command to vision subsystem ───────────────────────────────
    suspend fun handleVoiceCommandWithVision(
        command: String,
        currentFrame: Bitmap?,
        apiKey: String
    ): VisionVoiceResponse = withContext(Dispatchers.IO) {

        val lower = command.lowercase()
        Log.i(TAG, "Vision+Voice command: '$command'")

        return@withContext when {
            // "What do you see?" / "Describe what's in front of me"
            lower.containsAny("see", "look", "describe", "what is", "what's in") && currentFrame != null -> {
                val r = visionEngine.analyzeImage(currentFrame, "Describe what you see in 2-3 spoken sentences.", apiKey)
                val objects = extractObjects(r.description)
                objects.forEach { obj ->
                    objectMemory.remember(label = obj, description = r.description)
                }
                VisionVoiceResponse(
                    spokenText         = "I can see: ${r.description}",
                    visualDescription  = r.description,
                    detectedObjects    = objects,
                    memoryKey          = null,
                    confidence         = 0.9f
                )
            }

            // "Read this" / "What does this say?"
            lower.containsAny("read", "text", "say", "written", "sign") && currentFrame != null -> {
                val r = visionEngine.extractText(currentFrame, apiKey)
                VisionVoiceResponse(
                    spokenText         = if (r.description.isNotBlank()) "The text reads: ${r.description}" else "I couldn't read any text.",
                    visualDescription  = r.description,
                    detectedObjects    = emptyList(),
                    memoryKey          = null,
                    confidence         = 0.85f
                )
            }

            // "Remember what you see" / "Save this"
            lower.containsAny("remember", "save", "store", "memorize") && currentFrame != null -> {
                val r = visionEngine.analyzeImage(currentFrame, "Describe this scene in detail for memory storage.", apiKey)
                val objects = extractObjects(r.description)
                val key = "vision_${System.currentTimeMillis()}"
                objects.forEach { objectMemory.remember(label = it, description = r.description) }
                VisionVoiceResponse(
                    spokenText         = "I've saved this scene to memory. I can see: ${r.description.take(100)}",
                    visualDescription  = r.description,
                    detectedObjects    = objects,
                    memoryKey          = key,
                    confidence         = 0.9f
                )
            }

            // "Is there a [object] here?"
            lower.containsAny("is there", "can you find", "do you see", "find") && currentFrame != null -> {
                val target = extractTarget(command)
                val r = visionEngine.analyzeForObject(currentFrame, target, apiKey)
                val found = r.description.lowercase().let { "yes" in it || "i can see" in it || target in it }
                VisionVoiceResponse(
                    spokenText        = r.description,
                    visualDescription = r.description,
                    detectedObjects   = if (found) listOf(target) else emptyList(),
                    memoryKey         = null,
                    confidence        = if (found) 0.8f else 0.6f
                )
            }

            // "What did you see earlier?"
            lower.containsAny("earlier", "before", "previously", "remember seeing") -> {
                val recent = objectMemory.recallRecent(5)
                val spoken = if (recent.isEmpty()) "I don't recall seeing anything recently."
                else "Previously I saw: ${recent.joinToString(", ") { it.label }}"
                VisionVoiceResponse(
                    spokenText         = spoken,
                    visualDescription  = "",
                    detectedObjects    = recent.map { it.label },
                    memoryKey          = null,
                    confidence         = 0.95f
                )
            }

            else -> VisionVoiceResponse(
                spokenText        = "To use vision, I need to see your camera feed. Try saying 'What do you see?' when the camera is active.",
                visualDescription = "",
                detectedObjects   = emptyList(),
                memoryKey         = null,
                confidence        = 0.5f
            )
        }
    }

    // ── Narrate camera feed continuously ─────────────────────────────────────
    suspend fun narrateScene(bitmap: Bitmap, apiKey: String): String = withContext(Dispatchers.IO) {
        try {
            visionEngine.analyzeForVoiceNarration(bitmap, apiKey)
        } catch (e: Exception) {
            Log.e(TAG, "narrateScene failed: ${e.message}")
            "I'm having trouble analyzing the camera feed right now."
        }
    }

    // ── Enrich a voice query with current visual context ──────────────────────
    suspend fun enrichVoiceContext(
        userQuery: String,
        bitmap: Bitmap?,
        apiKey: String
    ): String {
        if (bitmap == null) return ""
        return try {
            val r = visionEngine.analyzeImage(bitmap, "In one sentence, describe the visual context relevant to: $userQuery", apiKey)
            "[Visual context: ${r.description}]"
        } catch (e: Exception) { "" }
    }

    // ── Recall what was seen and format for voice ─────────────────────────────
    fun buildMemoryContext(query: String): String = objectMemory.buildContextString(query)

    private fun extractObjects(description: String): List<String> {
        val words = description.split(" ", ",", ".", ";")
        val objectKeywords = setOf(
            "person", "people", "man", "woman", "child", "car", "truck", "bicycle", "phone",
            "laptop", "computer", "book", "chair", "table", "door", "window", "bottle", "cup",
            "dog", "cat", "bird", "tree", "plant", "building", "road", "sign", "screen"
        )
        return words.map { it.lowercase().trim() }
            .filter { it in objectKeywords }
            .distinct()
            .take(10)
    }

    private fun extractTarget(command: String): String {
        val patterns = listOf(
            Regex("is there (?:a |an )?([\\w\\s]+?)(?:\\?|$|here|in|on)"),
            Regex("find (?:a |an )?([\\w\\s]+?)(?:\\?|$)"),
            Regex("do you see (?:a |an )?([\\w\\s]+?)(?:\\?|$)")
        )
        for (p in patterns) {
            p.find(command.lowercase())?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotBlank()) return it
            }
        }
        return "object"
    }

    private fun String.containsAny(vararg keywords: String) = keywords.any { this.contains(it) }
}
