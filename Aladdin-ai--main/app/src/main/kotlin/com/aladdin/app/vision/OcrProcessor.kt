package com.aladdin.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OcrProcessor — Phase 7 Item 3: ML Kit Text Recognition with multi-language support.
 *
 * Implementation:
 *  • Dedicated ML Kit Text Recognition (Latin, Chinese, Japanese, Korean)
 *  • Bounding-box extraction per text block/line/element
 *  • Live OCR mode: processes CameraX frames every 2 seconds
 *  • Language auto-selection based on script
 *
 * Dependencies in app/build.gradle.kts:
 *   implementation("com.google.mlkit:text-recognition:16.0.1")
 *   implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
 *   implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
 *   implementation("com.google.mlkit:text-recognition-korean:16.0.1")
 */
@Singleton
class OcrProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG           = "OcrProcessor"
        private const val LIVE_OCR_DELAY_MS = 2_000L   // Phase 7 requirement
    }

    // ── Supported scripts ─────────────────────────────────────────────────────

    enum class Script { LATIN, CHINESE, JAPANESE, KOREAN }

    // ── Data models ───────────────────────────────────────────────────────────

    data class TextBlock(
        val text      : String,
        val boundingBox: RectF,
        val confidence: Float,
        val lines     : List<TextLine>
    )

    data class TextLine(
        val text      : String,
        val boundingBox: RectF,
        val elements  : List<TextElement>
    )

    data class TextElement(
        val text      : String,
        val boundingBox: RectF
    )

    data class OcrResult(
        val fullText  : String,
        val blocks    : List<TextBlock>,
        val script    : Script,
        val timestampMs: Long = System.currentTimeMillis()
    )

    // ── Recognizers (lazy — created on first use) ─────────────────────────────

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    // ── Live OCR flow ─────────────────────────────────────────────────────────

    private val _liveOcrResult = MutableSharedFlow<OcrResult>(
        replay             = 1,
        onBufferOverflow   = BufferOverflow.DROP_OLDEST
    )
    val liveOcrResult: SharedFlow<OcrResult> = _liveOcrResult

    private var liveOcrJob: kotlinx.coroutines.Job? = null
    private val liveScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var latestBitmap: Bitmap? = null

    // ── One-shot OCR ──────────────────────────────────────────────────────────

    /**
     * Recognise text in [bitmap] using [script] recogniser.
     * Returns structured [OcrResult] with bounding boxes per block, line, and word.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        script: Script = Script.LATIN
    ): OcrResult = withContext(Dispatchers.Main) {
        try {
            val image      = InputImage.fromBitmap(bitmap, 0)
            val recognizer = recognizerFor(script)
            val result     = recognizer.process(image).await()
            parseResult(result, script)
        } catch (e: Exception) {
            Log.e(TAG, "OCR error [$script]: ${e.message}")
            OcrResult("", emptyList(), script)
        }
    }

    /**
     * Attempt recognition with all scripts and return the result with the most text.
     */
    suspend fun recognizeAutoDetect(bitmap: Bitmap): OcrResult {
        val results = Script.entries.map { recognize(bitmap, it) }
        return results.maxByOrNull { it.fullText.length }
            ?: OcrResult("", emptyList(), Script.LATIN)
    }

    // ── Live OCR ──────────────────────────────────────────────────────────────

    /** Call this from the CameraX ImageAnalysis callback to feed frames. */
    fun onFrame(bitmap: Bitmap) { latestBitmap = bitmap }

    /**
     * Start processing frames from [onFrame] every [LIVE_OCR_DELAY_MS] milliseconds.
     * Results are emitted to [liveOcrResult].
     */
    fun startLiveOcr(script: Script = Script.LATIN) {
        if (liveOcrJob?.isActive == true) return
        Log.i(TAG, "Live OCR started (script=$script, interval=${LIVE_OCR_DELAY_MS}ms)")
        liveOcrJob = liveScope.launch {
            while (true) {
                delay(LIVE_OCR_DELAY_MS)
                val bmp = latestBitmap ?: continue
                try {
                    val result = recognize(bmp, script)
                    if (result.fullText.isNotBlank()) {
                        _liveOcrResult.emit(result)
                        Log.d(TAG, "Live OCR: ${result.fullText.take(60)}…")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Live OCR frame error: ${e.message}")
                }
            }
        }
    }

    fun stopLiveOcr() {
        liveOcrJob?.cancel()
        liveOcrJob = null
        Log.i(TAG, "Live OCR stopped")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun recognizerFor(script: Script): TextRecognizer = when (script) {
        Script.LATIN    -> latinRecognizer
        Script.CHINESE  -> chineseRecognizer
        Script.JAPANESE -> japaneseRecognizer
        Script.KOREAN   -> koreanRecognizer
    }

    private fun parseResult(result: Text, script: Script): OcrResult {
        val blocks = result.textBlocks.map { block ->
            val lines = block.lines.map { line ->
                val elements = line.elements.map { el ->
                    TextElement(el.text, el.boundingBox?.toRectF() ?: RectF())
                }
                TextLine(line.text, line.boundingBox?.toRectF() ?: RectF(), elements)
            }
            TextBlock(
                text        = block.text,
                boundingBox = block.boundingBox?.toRectF() ?: RectF(),
                confidence  = 1f,   // ML Kit does not expose per-block confidence directly
                lines       = lines
            )
        }
        val fullText = result.text
        Log.d(TAG, "Parsed OCR: ${blocks.size} blocks, ${fullText.length} chars")
        return OcrResult(fullText, blocks, script)
    }

    private fun android.graphics.Rect.toRectF() =
        RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    fun release() {
        stopLiveOcr()
        latinRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
    }
}
