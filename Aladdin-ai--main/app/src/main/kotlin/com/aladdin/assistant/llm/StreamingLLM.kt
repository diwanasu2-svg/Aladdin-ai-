package com.aladdin.assistant.llm

import android.content.Context
import android.util.Log
import com.aladdin.app.provider.ProviderConfig
import com.aladdin.llamacpp.LlamaCppEngine
import com.aladdin.voicecore.models.ModelDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini-only LLM backend.
 *
 * Provider priority:
 *   1. Gemini (Google Generative Language API) — default and only cloud backend.
 *      Requires the user to enter a Gemini API key in Settings.
 *   2. On-device llama.cpp — fully offline opt-in via
 *      `providerConfig.preferredProvider = "local"`.
 *
 * All Ollama / OpenAI-compatible HTTP endpoints have been removed.
 */
class StreamingLLM(
    private val providerConfig: ProviderConfig? = null,
    private val context: Context? = null,
    private val model: String = "gemini-1.5-flash"
) {
    companion object { private const val TAG = "StreamingLLM" }

    // ── On-device llama.cpp (opt-in only) ────────────────────────────────────
    private val localEngine: LlamaCppEngine? = context?.let { LlamaCppEngine(it) }
    @Volatile private var localInitAttempted = false

    private suspend fun ensureLocalModelReady(): Boolean {
        val engine = localEngine ?: return false
        if (engine.isReady) return true
        if (localInitAttempted) return engine.isReady
        localInitAttempted = true
        val ctx = context ?: return false
        val spec = ModelDownloader.DEFAULT_MODELS.first { it.id == "llama-3b-q4" }
        val modelFile = File(File(ctx.filesDir, spec.destDir), spec.destFileName ?: "model.gguf")
        if (!modelFile.exists()) {
            Log.w(TAG, "On-device GGUF model not downloaded yet at ${modelFile.absolutePath}")
            return false
        }
        return engine.init(modelPath = modelFile.absolutePath)
    }

    sealed class LlmEvent {
        data class Token(val text: String) : LlmEvent()
        data class SentenceComplete(val sentence: String) : LlmEvent()
        data class FullResponse(val text: String) : LlmEvent()
        data class Error(val message: String, val cause: Throwable? = null) : LlmEvent()
        object Done : LlmEvent()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val contextWindow = com.aladdin.app.llm.ConversationContextWindow()

    fun addSystemPrompt(prompt: String) { contextWindow.systemPrompt = prompt }
    fun clearHistory() { contextWindow.clear() }

    private val history: List<Map<String, String>>
        get() = contextWindow.getHistory().map { mapOf("role" to it.role, "content" to it.content) }

    fun streamMessage(userMessage: String): Flow<LlmEvent> = flow {
        contextWindow.addUserMessage(userMessage)

        val useLocalExplicitly = providerConfig?.preferredProvider == "local"
        val geminiConfigured   = providerConfig?.isGeminiConfigured == true

        when {
            useLocalExplicitly -> emitAll(streamLocal())
            geminiConfigured   -> emitAll(streamGemini())
            else -> emit(LlmEvent.Error(
                "Gemini API key not set. Open Settings → AI Provider and paste " +
                "a free key from aistudio.google.com/apikey, then tap Save."
            ))
        }
    }.flowOn(Dispatchers.IO)

    // ── On-device llama.cpp ───────────────────────────────────────────────────

    private fun streamLocal(): Flow<LlmEvent> = flow {
        if (localEngine == null) {
            emit(LlmEvent.Error(
                "No Gemini API key configured and on-device model requires a Context. " +
                "Please add a Gemini API key in Settings."
            ))
            return@flow
        }
        val ready = ensureLocalModelReady()
        if (!ready) {
            emit(LlmEvent.Error(
                "On-device AI model isn't downloaded yet. Open Settings → Download " +
                "Models, or add a Gemini API key in Settings to use Gemini instead."
            ))
            return@flow
        }
        val systemPrompt = contextWindow.systemPrompt.ifBlank { null }
        val userTurns = history
        val prompt = buildString {
            userTurns.forEach { msg ->
                append(if (msg["role"] == "assistant") "Assistant: " else "User: ")
                append(msg["content"]); append('\n')
            }
            append("Assistant: ")
        }
        val fullPrompt = if (systemPrompt != null) "$systemPrompt\n\n$prompt" else prompt

        val full = StringBuilder()
        val sentBuf = StringBuilder()
        val terminators = setOf('.', '!', '?')
        try {
            localEngine.completeStreaming(fullPrompt).collect { token ->
                if (token.isEmpty()) return@collect
                full.append(token); sentBuf.append(token)
                emit(LlmEvent.Token(token))
                if (token.any { it in terminators }) {
                    val next = sentBuf.toString().indexOfLast { it in terminators }
                    val sent = sentBuf.substring(0, next + 1).trim()
                    sentBuf.delete(0, next + 1)
                    if (sent.isNotBlank()) emit(LlmEvent.SentenceComplete(sent))
                }
            }
            val rem = sentBuf.toString().trim()
            if (rem.isNotBlank()) emit(LlmEvent.SentenceComplete(rem))
            val fullText = full.toString().trim()
            if (fullText.isBlank()) {
                emit(LlmEvent.Error("On-device model returned an empty response. Try again."))
                return@flow
            }
            contextWindow.addAssistantMessage(fullText)
            emit(LlmEvent.FullResponse(fullText))
            emit(LlmEvent.Done)
        } catch (e: Exception) {
            emit(LlmEvent.Error("On-device AI error: ${e.message}", e))
        }
    }.flowOn(Dispatchers.Default)

    // ── Gemini (Google Generative Language API) ───────────────────────────────

    private fun streamGemini(): Flow<LlmEvent> = flow {
        val key = providerConfig!!.geminiApiKey
        val geminiModel = providerConfig.geminiModel
        val geminiUrl = providerConfig.geminiBaseUrl.trimEnd('/')
        val url = "$geminiUrl/models/$geminiModel:generateContent?key=$key"

        val contents = JSONArray()
        history.forEach { msg ->
            val role = if (msg["role"] == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg["content"])))
            })
        }
        val systemPrompt = contextWindow.systemPrompt.ifBlank { null }

        val payload = JSONObject().apply {
            put("contents", contents)
            if (systemPrompt != null) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7); put("maxOutputTokens", 1024)
            })
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string()
                if (!resp.isSuccessful) {
                    val msg = when (resp.code) {
                        400 -> "Gemini: bad request — check that your API key is valid."
                        401, 403 -> "Gemini: invalid or expired API key. Update it in Settings → AI Provider."
                        429 -> "Gemini: rate limit hit. Wait a moment and try again."
                        else -> "Gemini HTTP ${resp.code}: ${bodyStr?.take(200)}"
                    }
                    emit(LlmEvent.Error(msg))
                    return@flow
                }
                if (bodyStr.isNullOrBlank()) { emit(LlmEvent.Error("Gemini: empty response")); return@flow }
                val text = try {
                    JSONObject(bodyStr)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optString("text", "") ?: ""
                } catch (e: Exception) {
                    emit(LlmEvent.Error("Gemini: parse failed — ${e.message}")); return@flow
                }
                if (text.isBlank()) {
                    emit(LlmEvent.Error("Gemini: empty response — check your API key and quota at aistudio.google.com"))
                    return@flow
                }
                emit(LlmEvent.Token(text))
                emit(LlmEvent.SentenceComplete(text))
                contextWindow.addAssistantMessage(text)
                emit(LlmEvent.FullResponse(text))
                emit(LlmEvent.Done)
            }
        } catch (e: IOException) { emit(LlmEvent.Error("Network error reaching Gemini: ${e.message}", e)) }
          catch (e: Exception)   { emit(LlmEvent.Error("Gemini error: ${e.message}", e)) }
    }.flowOn(Dispatchers.IO)
}
