package com.aladdin.vision.understanding

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "GeminiVisionService"
private const val DEFAULT_MODEL = "gemini-1.5-flash"

/**
 * GeminiVisionService integrates with Gemini Vision API for:
 *  - Image captioning
 *  - Object recognition
 *  - Scene understanding
 *  - Arbitrary image Q&A
 *
 * Configure with your Gemini API key via [configure] before use.
 * Obtain a key at: https://aistudio.google.com/app/apikey
 */
class GeminiVisionService(private val context: Context) {

    private var model: GenerativeModel? = null

    fun configure(apiKey: String) {
        model = GenerativeModel(
            modelName = DEFAULT_MODEL,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.4f
                maxOutputTokens = 1024
            }
        )
        Log.d(TAG, "Gemini Vision configured")
    }

    // ─── Image Captioning ────────────────────────────────────────────────────

    suspend fun captionImage(bitmap: Bitmap): VisionUnderstandingResult = withContext(Dispatchers.IO) {
        runVisionPrompt(
            bitmap = bitmap,
            prompt = """Describe this image in a clear, concise caption (1–2 sentences).
                |Focus on the main subject, key visual elements, and overall composition.
                |Be factual and precise.""".trimMargin()
        )
    }

    // ─── Object Recognition ──────────────────────────────────────────────────

    suspend fun recognizeObjects(bitmap: Bitmap): VisionUnderstandingResult = withContext(Dispatchers.IO) {
        runVisionPrompt(
            bitmap = bitmap,
            prompt = """List all distinct objects visible in this image.
                |For each object, provide:
                |1. Name
                |2. Location in the image (e.g. top-left, center, background)
                |3. Approximate size relative to the frame (small/medium/large)
                |4. Condition or notable attributes
                |Format as a structured list.""".trimMargin()
        )
    }

    // ─── Scene Understanding ─────────────────────────────────────────────────

    suspend fun understandScene(bitmap: Bitmap): VisionUnderstandingResult = withContext(Dispatchers.IO) {
        runVisionPrompt(
            bitmap = bitmap,
            prompt = """Analyze this scene and provide:
                |1. SCENE TYPE: indoor/outdoor, setting (office, street, nature, etc.)
                |2. TIME OF DAY: if determinable
                |3. LIGHTING CONDITIONS: natural, artificial, bright, dim, etc.
                |4. MAIN SUBJECTS: people, objects, or animals in focus
                |5. ACTIVITIES: any actions taking place
                |6. CONTEXT: the likely purpose or story behind this scene
                |7. MOOD/ATMOSPHERE: the emotional tone of the scene
                |Be concise and factual.""".trimMargin()
        )
    }

    // ─── Arbitrary Q&A ───────────────────────────────────────────────────────

    suspend fun askAboutImage(bitmap: Bitmap, question: String): VisionUnderstandingResult =
        withContext(Dispatchers.IO) {
            runVisionPrompt(
                bitmap = bitmap,
                prompt = question
            )
        }

    // ─── Core runner ─────────────────────────────────────────────────────────

    private suspend fun runVisionPrompt(bitmap: Bitmap, prompt: String): VisionUnderstandingResult {
        val currentModel = model ?: return VisionUnderstandingResult.error(
            "Gemini not configured. Call VisionModule.configureGemini(apiKey) first."
        )

        return try {
            val response = currentModel.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )
            val text = response.text ?: return VisionUnderstandingResult.error("Empty response from Gemini")
            VisionUnderstandingResult.success(text, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Vision call failed", e)
            VisionUnderstandingResult.error("Gemini error: ${e.message}", e)
        }
    }
}
