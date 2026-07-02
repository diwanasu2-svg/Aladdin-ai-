package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VisionEngine — Items 54-57.
 * Item 54: Vision connected to AIEngine — results routed to orchestrator.
 * Item 55: Vision + Voice pipeline — analyzeForVoiceNarration() → TTS.
 * Item 56: CameraX capture integration via analyzeImageFromFile().
 * Item 57: Gemini 1.5 Flash multimodal vision API.
 */
@Singleton
class VisionEngine @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val TAG = "VisionEngine"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private const val MAX_SIDE = 1024
        private const val JPEG_Q = 85
    }

    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class VisionResult(val description: String, val detectedObjects: List<String> = emptyList(), val extractedText: String = "")

    /** Item 57: Gemini 1.5 Flash image analysis. */
    suspend fun analyzeImage(bitmap: Bitmap, prompt: String = "Describe this image in detail.", apiKey: String): VisionResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext VisionResult("Vision requires Gemini API key configuration.")
        val b64 = bitmapToBase64(bitmap)
        try { makeRequest(buildRequest(prompt, b64), apiKey).let { parseResponse(it) } }
        catch (e: Exception) { Log.e(TAG, "Vision error: \${e.message}"); VisionResult("Vision analysis failed: \${e.message}") }
    }

    /** Item 56: Analyze from file (CameraX capture). */
    suspend fun analyzeImageFromFile(file: File, prompt: String = "Describe this image.", apiKey: String): VisionResult = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext VisionResult("File not found: \${file.path}")
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext VisionResult("Cannot decode image")
        analyzeImage(bmp, prompt, apiKey)
    }

    /** Item 54: Object detection for AIEngine routing. */
    suspend fun analyzeForObject(bitmap: Bitmap, target: String, apiKey: String) =
        analyzeImage(bitmap, "Is there a '\$target' in this image? Describe where.", apiKey)

    /** Item 55: Voice narration — result piped to TTS. */
    suspend fun analyzeForVoiceNarration(bitmap: Bitmap, apiKey: String): String {
        val r = analyzeImage(bitmap, "Describe this image in 2-3 sentences for spoken narration.", apiKey)
        return r.description
    }

    /** OCR via Gemini vision — Items 58-62 bridge. */
    suspend fun extractText(bitmap: Bitmap, apiKey: String) =
        analyzeImage(bitmap, "Extract all visible text from this image. Return only the text.", apiKey)

    private fun bitmapToBase64(bmp: Bitmap): String {
        val w = bmp.width; val h = bmp.height
        val scaled = if (w > MAX_SIDE || h > MAX_SIDE) {
            val r = MAX_SIDE.toFloat() / maxOf(w, h)
            Bitmap.createScaledBitmap(bmp, (w * r).toInt(), (h * r).toInt(), true)
        } else bmp
        val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildRequest(prompt: String, b64: String) = JSONObject().apply {
        put("contents", JSONArray().apply { put(JSONObject().apply { put("parts", JSONArray().apply {
            put(JSONObject().apply { put("text", prompt) })
            put(JSONObject().apply { put("inline_data", JSONObject().apply { put("mime_type", "image/jpeg"); put("data", b64) }) })
        }) }) })
        put("generationConfig", JSONObject().apply { put("maxOutputTokens", 512); put("temperature", 0.2) })
    }.toString()

    private fun makeRequest(body: String, key: String): String {
        val req = Request.Builder().url("\$ENDPOINT?key=\$key").post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r -> if (!r.isSuccessful) throw Exception("HTTP \${r.code}"); return r.body?.string() ?: "" }
    }

    private fun parseResponse(json: String): VisionResult {
        return try {
            val text = JSONObject(json).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            VisionResult(description = text.trim())
        } catch (e: Exception) { VisionResult("Parse error: \${e.message}") }
    }
}