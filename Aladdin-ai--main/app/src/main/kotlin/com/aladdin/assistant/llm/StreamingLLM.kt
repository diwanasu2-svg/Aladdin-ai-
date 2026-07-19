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
 *      ollamaBaseUrl + ollamaApiPath — the default, same as the original
 *      app. Point this at a real running server: on-device via
 *      PRoot/Termux `ollama serve` (http://127.0.0.1:11434), a LAN/cloud
 *      Ollama box, or any https:// tunnel (ngrok, Cloudflare Tunnel, etc).
 *   3. On-device llama.cpp — only used if the user explicitly sets
 *      `preferredProvider = "local"` in Settings.
 *
 * Custom-endpoint fix (2026-07-08): base URL, HTTPS, optional port, and the
 * API path ("/v1" OpenAI-compatible vs "/api" native Ollama) are all now
 * configurable from Settings via [ProviderConfig] instead of being baked
 * into a fixed "http://host:port/v1" shape.
 */
class StreamingLLM(
    private val providerConfig: ProviderConfig? = null,
    private val context: Context? = null,
    private val baseUrl: String = "http://10.159.85.23:11434/v1",
    private val apiKey: String = "ollama",
    private val model: String = "llama3.2:3b"
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

    // Model-quality fix (2026-07-08): this used to be a raw, unbounded
    // mutableListOf<Map<String,String>> — every single turn of a conversation
    // stayed in memory and got resent on every request forever. A fully built
    // token-budget-aware trimmer (ConversationContextWindow, "Items 27-29") already
    // existed in the codebase but nothing ever constructed or called it, so long
    // conversations would eventually either blow past the model's context window
    // (silent truncation / HTTP errors from Ollama, or Gemini rejecting the
    // request) or just get slower and less coherent turn after turn as stale
    // early messages diluted the model's attention. Now wired in for real.
    private val contextWindow = com.aladdin.app.llm.ConversationContextWindow()

    fun addSystemPrompt(prompt: String) { contextWindow.systemPrompt = prompt }
    fun clearHistory() { contextWindow.clear() }

    /** Non-system turns only, in order — used by providers that keep system separate. */
    private val history: List<Map<String, String>>
        get() = contextWindow.getHistory().map { mapOf("role" to it.role, "content" to it.content) }

    fun streamMessage(userMessage: String): Flow<LlmEvent> = flow {
        contextWindow.addUserMessage(userMessage)

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
                contextWindow.addAssistantMessage(text)
                emit(LlmEvent.FullResponse(text))
                emit(LlmEvent.Done)
            }
        } catch (e: IOException) { emit(LlmEvent.Error("Network: ${e.message}", e)) }
          catch (e: Exception)   { emit(LlmEvent.Error("Error: ${e.message}", e)) }
    }.flowOn(Dispatchers.IO)

    // ── OpenAI-compatible endpoint (Ollama / LM Studio / cloud) — DEFAULT ─────
    //
    // Custom-endpoint fix (2026-07-08): this used to hardcode
    // "http://<host>:<port>/v1/chat/completions" — no https, no custom
    // port-less URLs (e.g. an ngrok tunnel), and no way to talk to a
    // server that only exposes Ollama's native "/api/chat" instead of an
    // OpenAI-compatible "/v1/chat/completions" route. Now the full base
    // URL + endpoint style both come from Settings (ProviderConfig), and
    // failures are split into distinct, actionable messages instead of one
    // generic "Network: ..." string.

    private fun streamOpenAiCompatible(): Flow<LlmEvent> = flow {
        val effectiveModel = providerConfig?.ollamaModel ?: model
        val nativeApi = providerConfig?.isNativeOllamaApi == true
        val chatUrl = providerConfig?.ollamaChatUrl() ?: "$baseUrl/chat/completions"

        val bodyJson = if (nativeApi) buildNativeOllamaJson(effectiveModel) else buildJson(effectiveModel)
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(chatUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body).build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try { resp.body?.string() } catch (_: Exception) { null }
                    emit(LlmEvent.Error(friendlyHttpError(resp.code, chatUrl, effectiveModel, errBody)))
                    return@flow
                }
                val bodyStr = resp.body?.string() ?: run {
                    emit(LlmEvent.Error("Empty body from $chatUrl")); return@flow
                }
                // stream: false — response is a single JSON object, not SSE/NDJSON
                val token: String? = if (nativeApi) {
                    // Ollama native /api/chat: {"message":{"role":"assistant","content":"..."},"done":true}
                    try { JSONObject(bodyStr).optJSONObject("message")?.optString("content", null) }
                    catch (_: Exception) { null }
                } else {
                    // OpenAI-compatible: {"choices":[{"message":{"role":"assistant","content":"..."}}]}
                    try {
                        JSONObject(bodyStr).optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", null)
                    } catch (_: Exception) { null }
                }
                if (token.isNullOrBlank()) {
                    emit(LlmEvent.Error("Server at $chatUrl returned an empty response. Check the model name ('$effectiveModel') and API path in Settings."))
                    return@flow
                }
                emit(LlmEvent.Token(token))
                emit(LlmEvent.SentenceComplete(token))
                contextWindow.addAssistantMessage(token)
                emit(LlmEvent.FullResponse(token))
                emit(LlmEvent.Done)
            }
        } catch (e: java.net.SocketTimeoutException) {
            emit(LlmEvent.Error("Timeout — $chatUrl didn't respond in time. Is the server slow/overloaded, or is a VPN/tunnel dropping the connection?", e))
        } catch (e: java.net.ConnectException) {
            emit(LlmEvent.Error("Connection refused — nothing is listening at $chatUrl. Check Settings → Server URL, and make sure the server is running.", e))
        } catch (e: java.net.UnknownHostException) {
            emit(LlmEvent.Error("Can't resolve host for $chatUrl — check the Server URL in Settings for typos (missing https://, wrong domain, etc.).", e))
        } catch (e: javax.net.ssl.SSLException) {
            emit(LlmEvent.Error("HTTPS/TLS error talking to $chatUrl — ${e.message}. If this is a self-signed cert, use http:// instead or fix the server's certificate.", e))
        } catch (e: IOException) {
            emit(LlmEvent.Error("Network error reaching $chatUrl — ${e.message}. Check Settings, or add a Gemini API key instead.", e))
        } catch (e: Exception) { emit(LlmEvent.Error("Error: ${e.message}", e)) }
    }.flowOn(Dispatchers.IO)

    /** Turns a failed HTTP response into one of several distinct, specific error messages. */
    private fun friendlyHttpError(code: Int, url: String, model: String, bodySnippet: String?): String {
        val bodyLower = bodySnippet?.lowercase() ?: ""
        return when {
            code == 404 && bodyLower.contains("model") -> "Model not found — '$model' isn't available on this server. Pull it there first (e.g. `ollama pull $model`) or fix the model name in Settings."
            code == 404 -> "404 Not Found at $url — the endpoint path looks wrong for this server. In Settings, try switching the API path between '/v1' (OpenAI-compatible) and '/api' (native Ollama)."
            code == 400 && bodyLower.contains("model") -> "Model not found — the server rejected model '$model'. Check the exact installed model name (e.g. `ollama list`) and update Settings."
            code == 401 || code == 403 -> "Unauthorized (HTTP $code) at $url — this server requires authentication that isn't configured."
            code in 500..599 -> "Server error (HTTP $code) at $url — the AI server itself is failing; check its logs. ${bodySnippet?.take(150) ?: ""}"
            else -> "HTTP $code from $url — ${bodySnippet?.take(200) ?: "no details"}"
        }
    }

    private fun buildJson(effectiveModel: String): String {
        val msgs = JSONArray()
        if (contextWindow.systemPrompt.isNotBlank()) {
            msgs.put(JSONObject(mapOf("role" to "system", "content" to contextWindow.systemPrompt)))
        }
        history.forEach { msgs.put(JSONObject(it)) }
        return JSONObject().apply {
            put("model", effectiveModel); put("messages", msgs)
            put("stream", false); put("temperature", 0.7); put("max_tokens", 1024)
        }.toString()
    }

    /** Payload for Ollama's native /api/chat route (no "max_tokens"/OpenAI-only fields). */
    private fun buildNativeOllamaJson(effectiveModel: String): String {
        val msgs = JSONArray()
        if (contextWindow.systemPrompt.isNotBlank()) {
            msgs.put(JSONObject(mapOf("role" to "system", "content" to contextWindow.systemPrompt)))
        }
        history.forEach { msgs.put(JSONObject(it)) }
        return JSONObject().apply {
            put("model", effectiveModel); put("messages", msgs)
            put("stream", false)
            put("options", JSONObject().apply { put("temperature", 0.7) })
        }.toString()
    }

    private fun parseToken(json: String): String? = try {
        JSONObject(json).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("delta")
            ?.optString("content", null)
    } catch (_: Exception) { null }
}
