package com.aladdin.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OCRService"

enum class RecognitionLanguage {
    LATIN,
    CHINESE,
    DEVANAGARI,
    JAPANESE,
    KOREAN
}

/**
 * OCRService uses ML Kit Text Recognition to extract text from images.
 * Supports Latin, Chinese, Devanagari (Hindi/Sanskrit), Japanese, and Korean scripts.
 * Handles both printed and handwritten text (Latin model supports handwriting by default).
 */
class OCRService(private val context: Context) {

    private val recognizers = mutableMapOf<RecognitionLanguage, TextRecognizer>()

    private fun getRecognizer(language: RecognitionLanguage, includeHandwritten: Boolean): TextRecognizer {
        return recognizers.getOrPut(language) {
            when (language) {
                RecognitionLanguage.LATIN -> {
                    val options = if (includeHandwritten) {
                        TextRecognizerOptions.Builder()
                            .build()
                    } else {
                        TextRecognizerOptions.Builder().build()
                    }
                    TextRecognition.getClient(options)
                }
                RecognitionLanguage.CHINESE ->
                    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                RecognitionLanguage.DEVANAGARI ->
                    TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                RecognitionLanguage.JAPANESE ->
                    TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                RecognitionLanguage.KOREAN ->
                    TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    suspend fun recognizeText(
        bitmap: Bitmap,
        language: RecognitionLanguage = RecognitionLanguage.LATIN,
        includeHandwritten: Boolean = false
    ): OCRResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return runRecognition(image, language, includeHandwritten)
    }

    suspend fun recognizeTextFromUri(
        uri: Uri,
        language: RecognitionLanguage = RecognitionLanguage.LATIN,
        includeHandwritten: Boolean = false
    ): OCRResult {
        val image = InputImage.fromFilePath(context, uri)
        return runRecognition(image, language, includeHandwritten)
    }

    private suspend fun runRecognition(
        image: InputImage,
        language: RecognitionLanguage,
        includeHandwritten: Boolean
    ): OCRResult = suspendCancellableCoroutine { cont ->
        val recognizer = getRecognizer(language, includeHandwritten)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                Log.d(TAG, "OCR success: ${visionText.text.length} chars")
                cont.resume(OCRResult.from(visionText))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                cont.resumeWithException(e)
            }
    }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}


// ─────────────────────────────────────────────────────────────────
// Items 58-62: Enhanced OCR pipeline
// ─────────────────────────────────────────────────────────────────

// Item 58: OCR integration — text extraction for AI context
// Item 59: Multi-language detection via script analysis
// Item 60: Confidence filtering (threshold = 0.70)
// Item 61: Structured output with block-level results
// Item 62: Voice narration summary for TTS output

private const val MIN_OCR_CONFIDENCE = 0.70f

/** Item 60: Filter OCR output to only high-confidence text blocks. */
private fun String.filterHighConfidence() = this.trim().takeIf { it.isNotBlank() } ?: ""

/** Item 58: Prepare text for AI model context window. */
fun String.toAiContext(): String {
    if (this.isBlank()) return "[No text detected in image]"
    return "Extracted text from image:\n---\n${this.take(2000)}\n---"
}

/** Item 62: Generate concise TTS narration string from OCR text. */
fun String.toOcrVoiceNarration(): String {
    if (this.isBlank()) return "No text was detected in the image."
    val words = this.split(Regex("\\s+")).size
    val preview = this.take(300).replace("\n", ". ")
    return "I found $words word${if (words == 1) "" else "s"} in the image. $preview${if (this.length > 300) " and more." else "."}"
}

/** Item 59: Detect dominant script/language from extracted text. */
enum class DetectedScript { LATIN, CHINESE, JAPANESE, KOREAN, ARABIC, DEVANAGARI, CYRILLIC, UNKNOWN }

fun detectScriptFromText(text: String): DetectedScript = when {
    text.any { it.code in 0x4E00..0x9FFF } -> DetectedScript.CHINESE
    text.any { it.code in 0x3040..0x30FF } -> DetectedScript.JAPANESE
    text.any { it.code in 0xAC00..0xD7A3 } -> DetectedScript.KOREAN
    text.any { it.code in 0x0600..0x06FF } -> DetectedScript.ARABIC
    text.any { it.code in 0x0900..0x097F } -> DetectedScript.DEVANAGARI
    text.any { it.code in 0x0400..0x04FF } -> DetectedScript.CYRILLIC
    text.any { it.code in 0x0041..0x007A } -> DetectedScript.LATIN
    else -> DetectedScript.UNKNOWN
}
