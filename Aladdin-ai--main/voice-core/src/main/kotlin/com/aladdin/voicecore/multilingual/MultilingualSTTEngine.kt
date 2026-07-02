package com.aladdin.voicecore.multilingual

import android.content.Context
import android.util.Log
import com.aladdin.voicecore.models.ErrorCode
import com.aladdin.voicecore.models.VoiceCoreConfig
import com.aladdin.voicecore.models.VoiceCoreEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Multilingual Speech-to-Text Engine — Features 1, 2, 3, 10, 11.
 *
 * Supports Hindi, Gujarati, and English via:
 *  - Vosk offline models per language
 *  - Whisper HTTP bridge for higher accuracy (optional)
 *  - Automatic language detection after transcription
 *  - Language-tagged transcript events for memory/TTS routing
 *  - Unicode-safe transcript handling (Devanagari, Gujarati, Latin)
 *
 * Architecture:
 *  - Loads Vosk model for the active language on demand.
 *  - Switches language at runtime in <500 ms (model reload).
 *  - Language is auto-detected from the Whisper transcript when auto mode is on.
 */
class MultilingualSTTEngine(
    private val context: Context,
    private val config: VoiceCoreConfig,
    private val mlConfig: MultilingualConfig,
    private val audioFrames: ReceiveChannel<ShortArray>,
) {
    companion object {
        private const val TAG = "MultilingualSTTEngine"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<VoiceCoreEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<VoiceCoreEvent> = _events

    // Language detection
    private val detector = LanguageDetector(
        defaultLanguage = mlConfig.defaultLanguage,
        confidenceThreshold = mlConfig.detectionConfidenceThreshold,
        historyWindow = mlConfig.detectionHistoryWindow,
        historyWeight = mlConfig.detectionHistoryWeight,
    )

    // Vosk models keyed by language code — loaded on demand
    private val voskModels = mutableMapOf<String, Model>()
    private val voskRecognizers = mutableMapOf<String, Recognizer>()

    // Currently active language (changes after each detection)
    @Volatile private var activeLanguage: String = mlConfig.defaultLanguage
    @Volatile private var isListening = false
    @Volatile private var lastSpeechMs = 0L
    private val uttBuffer = mutableListOf<ShortArray>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialise Vosk model for [language]. Call this on startup
     * and whenever the user switches language.
     */
    fun initLanguage(language: String) {
        if (voskModels.containsKey(language)) {
            Log.d(TAG, "Vosk model for '$language' already loaded")
            return
        }
        val modelPath = "${context.filesDir}/${mlConfig.sttModelPathFor(language)}"
        try {
            val model = Model(modelPath)
            val recognizer = Recognizer(model, config.sampleRateHz.toFloat()).apply {
                setMaxAlternatives(3)
                setWords(true)
            }
            voskModels[language] = model
            voskRecognizers[language] = recognizer
            Log.i(TAG, "STT model loaded for '$language' from $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "STT model load failed for '$language': ${e.message}")
            scope.launch {
                _events.emit(VoiceCoreEvent.Error(
                    ErrorCode.STT_MODEL_NOT_FOUND,
                    "STT model not found for $language at $modelPath",
                    e
                ))
            }
        }
    }

    /** Initialise all supported language models. */
    fun initAll() {
        for (lang in mlConfig.supportedLanguages) {
            initLanguage(lang)
        }
    }

    fun startListening(language: String? = null) {
        if (isListening) return
        activeLanguage = language ?: mlConfig.defaultLanguage
        isListening = true
        lastSpeechMs = System.currentTimeMillis()
        uttBuffer.clear()
        voskRecognizers[activeLanguage]?.reset()
        Log.i(TAG, "STT: start listening (language=$activeLanguage)")
        scope.launch { recognitionLoop() }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        scope.launch { emitFinalResult() }
    }

    fun setLanguage(language: String) {
        if (!voskModels.containsKey(language)) {
            initLanguage(language)
        }
        activeLanguage = language
        voskRecognizers[language]?.reset()
        Log.i(TAG, "STT language switched to '$language'")
    }

    fun shutdown() {
        isListening = false
        scope.cancel()
        voskRecognizers.values.forEach { runCatching { it.close() } }
        voskModels.values.forEach { runCatching { it.close() } }
        voskRecognizers.clear()
        voskModels.clear()
        Log.i(TAG, "MultilingualSTTEngine shutdown")
    }

    // ── Recognition loop ──────────────────────────────────────────────────────

    private suspend fun recognitionLoop() {
        _events.emit(VoiceCoreEvent.SpeechStarted())

        while (isListening && scope.isActive) {
            val frame = audioFrames.tryReceive().getOrNull()

            if (frame == null) {
                val silenceMs = System.currentTimeMillis() - lastSpeechMs
                if (silenceMs >= config.silenceTimeoutMs) {
                    Log.i(TAG, "Silence timeout – ending utterance")
                    emitFinalResult()
                    isListening = false
                    break
                }
                kotlinx.coroutines.delay(10)
                continue
            }

            lastSpeechMs = System.currentTimeMillis()
            uttBuffer.add(frame)

            val bytes = shortsToBytes(frame)
            val rec = voskRecognizers[activeLanguage] ?: continue

            try {
                if (rec.acceptWaveForm(bytes, bytes.size)) {
                    val text = extractText(rec.result, "text")
                    if (text.isNotBlank()) {
                        val langResult = detector.detect(text)
                        // Auto-switch if a different language is detected with high confidence
                        if (langResult.language != activeLanguage && langResult.confidence > 0.7f) {
                            Log.i(TAG, "Auto-switching STT language: '$activeLanguage' → '${langResult.language}'")
                            activeLanguage = langResult.language
                            voskRecognizers[activeLanguage]?.reset()
                        }
                        _events.emit(VoiceCoreEvent.Transcript(text, isFinal = false))
                    }
                } else {
                    val partial = extractText(rec.partialResult, "partial")
                    if (partial.isNotBlank()) {
                        _events.emit(VoiceCoreEvent.Transcript(partial, isFinal = false))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error: ${e.message}", e)
            }
        }

        _events.emit(VoiceCoreEvent.SpeechEnded())
    }

    private suspend fun emitFinalResult() {
        val rec = voskRecognizers[activeLanguage] ?: run {
            // Try Whisper HTTP bridge as fallback
            val collectedAudio = uttBuffer.flatMap { it.toList() }.toShortArray()
            val bytes = shortsToBytes(collectedAudio)
            val whisperText = streamToWhisper(bytes)
            if (!whisperText.isNullOrBlank()) {
                val langResult = detector.detect(whisperText)
                activeLanguage = langResult.language
                _events.emit(VoiceCoreEvent.Transcript(whisperText, isFinal = true))
            }
            uttBuffer.clear()
            return
        }

        try {
            val finalJson = rec.finalResult
            val text = extractText(finalJson, "text")
            if (text.isNotBlank()) {
                // Detect language from final transcript
                val langResult = detector.detect(text)
                Log.i(TAG, "Final transcript: \"$text\" → language=${langResult.language} (${langResult.confidence})")
                activeLanguage = langResult.language
                _events.emit(VoiceCoreEvent.Transcript(text, isFinal = true))
            }
            rec.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Final result error: ${e.message}", e)
        }
        uttBuffer.clear()
    }

    // ── Whisper HTTP bridge (multilingual, Features 1, 2) ─────────────────────

    /**
     * Stream audio to a local faster-whisper server.
     * Server returns transcript and detected language.
     * Start with: faster-whisper-server --model base --language auto --port 8765
     */
    private suspend fun streamToWhisper(audioBytes: ByteArray): String? {
        return try {
            val url = java.net.URL(mlConfig.whisperServerUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "audio/pcm")
            conn.setRequestProperty("X-Sample-Rate", config.sampleRateHz.toString())
            // Tell Whisper to auto-detect language or use active language
            if (!mlConfig.whisperLanguageAutoDetect) {
                conn.setRequestProperty("X-Language", activeLanguage)
            }
            conn.doOutput = true
            conn.connectTimeout = config.sttTargetLatencyMs
            conn.readTimeout = config.sttTargetLatencyMs * 3
            conn.outputStream.use { it.write(audioBytes) }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText().trim()
                // If server returns JSON with language, extract it
                val detectedLang = conn.getHeaderField("X-Detected-Language")
                if (!detectedLang.isNullOrBlank()) {
                    activeLanguage = detectedLang
                    Log.i(TAG, "Whisper detected language: $detectedLang")
                }
                response
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Whisper bridge error: ${e.message}")
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    val currentLanguage: String get() = activeLanguage

    private fun extractText(json: String, key: String): String {
        val marker = "\"$key\""
        val start = json.indexOf(marker)
        if (start < 0) return ""
        val valStart = json.indexOf('"', start + marker.length + 1) + 1
        val valEnd = json.indexOf('"', valStart)
        return if (valStart > 0 && valEnd > valStart) json.substring(valStart, valEnd).trim() else ""
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2]     = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
