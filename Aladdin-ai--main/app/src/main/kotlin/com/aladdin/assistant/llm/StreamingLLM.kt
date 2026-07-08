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
 * Phase 2 – Streaming LLM
 *
 * Bug fix (2026-07-05): this class used to be constructed with ALL-DEFAULT
 * parameters (`StreamingLLM()`), hardcoded to a local Ollama server at
 * `localhost:11434`. It completely ignored the Gemini / OpenAI / Anthropic
 * settings the user configures in the Settings screen (backed by
 * [ProviderConfig]). If Ollama wasn't actually running on the device, every
 * single chat message failed with a silent [LlmEvent.Error] that the
 * orchestrator swallowed without ever showing anything in the chat UI.
 *
 * Bug fix (2026-07-07): briefly switched the default to a fully on-device
 * llama.cpp engine (see :llama-cpp) to avoid needing any server at all.
 *
 * Bug fix (2026-07-08): reverted back to an Ollama/OpenAI-compatible HTTP
 * server as the DEFAULT path — the on-device engine was too slow and hung
 * on this hardware. Priority order now:
 *   1. Gemini (only if the user explicitly added an API key in Settings)
 *   2. Ollama / OpenAI-compatible HTTP endpoint at ProviderConfig's
 *      ollamaHost:ollamaPort — the default, same as the original app.
 *      Point this at a real running Ollama server (on-device via
 *      PRoot/Termux `ollama serve`, or a PC/cloud server on your network).
 *   3. On-device llama.cpp — only used if the user explicitly sets
 *      `preferredProvider = "local"` in Settings.
 */
class StreamingLLM(
    private val providerConfig: ProviderConfig? = null,
    private val context: Context? = null,
    private val baseUrl: String = "http://127.0.0.1:11434/v1",
    private val apiKey: String = "ollama",
    private val model: String = "llama3.2"
) {
    companion object { private const val TAG = "StreamingLLM" }

    // ── On-device llama.cpp (opt-in fallback only — set preferredProvider = "local") ──
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
            Log.w(TAG, "On-device GGUF model not downloaded yet at ${modelFile.absolutePath} — " +
                "trigger ModelDownloader.downloadAll() from Settings/first-run to fetch it once.")
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

    private val history = mutableListOf<Map<String, String>>()

    fun addSystemPrompt(prompt: String) { history.add(0, mapOf("role" to "system", "content" to prompt)) }
    fun clearHistory() { history.clear() }

    fun streamMessage(userMessage: String): Flow<LlmEvent> = flow {
        history.add(mapOf("role" to "user", "content" to userMessage))

        val useGemini = providerConfig?.isGeminiConfigured == true
        val useLocalExplicitly = providerConfig?.preferredProvider == "local"

        when {
            useGemini -> emitAll(streamGemini())
            useLocalExplicitly -> emitAll(streamLocal())
            else -> emitAll(streamOpenAiCompatible())
        }
    }.flowOn(Dispatchers.IO)

    // ── Local on-device llama.cpp (opt-in only — no server, no internet) ──────

    private fun streamLocal(): Flow<LlmEvent> = flow {
        if (localEngine == null) {
            // No Context was supplied to this StreamingLLM instance — fall back
            // to whatever HTTP endpoint is configured rather than crashing.
            emitAll(streamOpenAiCompatible())
            return@flow
        }
        val ready = ensureLocalModelReady()
        if (!ready) {
            emit(LlmEvent.Error(
                "On-device AI model isn't downloaded yet. Open Settings → Download " +
                    "Models to fetch the offline model once (no internet needed afterwards), " +
                    "or add a Gemini API key in Settings to use the cloud instead."
            ))
            return@flow
        }
        val systemPrompt = history.firstOrNull { it["role"] == "system" }?.get("content")
        val userTurns = history.filter { it["role"] != "system" }
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
            history.add(mapOf("role" to "assistant", "content" to fullText))
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
            if (msg["role"] == "system") return@forEach // Gemini uses systemInstruction separately
            val role = if (msg["role"] == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg["content"])))
            })
        }
        val systemPrompt = history.firstOrNull { it["role"] == "system" }?.get("content")

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
                    emit(LlmEvent.Error("Gemini HTTP ${resp.code}: ${bodyStr?.take(200)}"))
                    return@flow
                }
                if (bodyStr.isNullOrBlank()) { emit(LlmEvent.Error("Gemini: empty body")); return@flow }
                val text = try {
                    JSONObject(bodyStr)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optString("text", "") ?: ""
                } catch (e: Exception) {
                    emit(LlmEvent.Error("Gemini: parse failed — ${e.message}")); return@flow
                }
                if (text.isBlank()) {
                    emit(LlmEvent.Error("Gemini: no response text (check API key / model / quota)"))
                    return@flow
                }
                emit(LlmEvent.Token(text))
                emit(LlmEvent.SentenceComplete(text))
                history.add(mapOf("role" to "assistant", "content" to text))
                emit(LlmEvent.FullResponse(text))
                emit(LlmEvent.Done)
            }
        } catch (e: IOException) { emit(LlmEvent.Error("Network: ${e.message}", e)) }
          catch (e: Exception)   { emit(LlmEvent.Error("Error: ${e.message}", e)) }
    }.flowOn(Dispatchers.IO)

    // ── OpenAI-compatible endpoint (Ollama / LM Studio / cloud) — DEFAULT ─────

    private fun streamOpenAiCompatible(): Flow<LlmEvent> = flow {
        val effectiveBaseUrl = providerConfig?.let { "http://${it.ollamaHost}:${it.ollamaPort}/v1" } ?: baseUrl
        val effectiveModel = providerConfig?.ollamaModel ?: model

        val bodyJson = buildJson(effectiveModel)
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$effectiveBaseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body).build()

        val full = StringBuilder()
        val sentBuf = StringBuilder()
        val terminators = setOf('.', '!', '?')

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(LlmEvent.Error("Ollama HTTP ${resp.code} — is 'ollama serve' running with model '$effectiveModel' pulled? Check Settings for the correct host/port ($effectiveBaseUrl)."))
                    return@flow
                }
                val src = resp.body?.source() ?: run { emit(LlmEvent.Error("Empty body")); return@flow }
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val token = parseToken(data) ?: continue
                    if (token.isEmpty()) continue
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
                val fullText = full.toString()
                history.add(mapOf("role" to "assistant", "content" to fullText))
                emit(LlmEvent.FullResponse(fullText))
                emit(LlmEvent.Done)
            }
        } catch (e: IOException) {
            emit(LlmEvent.Error("Network: ${e.message} — is 'ollama serve' running and reachable at ${providerConfig?.let { "${it.ollamaHost}:${it.ollamaPort}" } ?: baseUrl}? Check Settings, or add a Gemini API key instead.", e))
        } catch (e: Exception) { emit(LlmEvent.Error("Error: ${e.message}", e)) }
    }.flowOn(Dispatchers.IO)

    private fun buildJson(effectiveModel: String): String {
        val msgs = JSONArray(); history.forEach { msgs.put(JSONObject(it)) }
        return JSONObject().apply {
            put("model", effectiveModel); put("messages", msgs)
            put("stream", true); put("temperature", 0.7); put("max_tokens", 1024)
        }.toString()
    }

    private fun parseToken(json: String): String? = try {
        JSONObject(json).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("delta")
            ?.optString("content", null)
    } catch (_: Exception) { null }
}
