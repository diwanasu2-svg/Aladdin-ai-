package com.aladdin.assistant.llm

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phase 2 – Streaming LLM
 * Connects to an OpenAI-compatible streaming endpoint (Ollama / LM Studio / cloud).
 * Emits tokens as they arrive so the UI shows a live typing effect and TTS can
 * start speaking the first sentence without waiting for the full response.
 */
class StreamingLLM(
    private val baseUrl: String = "http://localhost:11434/v1",
    private val apiKey: String = "ollama",
    private val model: String = "mistral"
) {
    companion object { private const val TAG = "StreamingLLM" }

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
        val body = buildJson().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body).build()

        val full = StringBuilder()
        val sentBuf = StringBuilder()
        val terminators = setOf('.', '!', '?')

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { emit(LlmEvent.Error("HTTP ${resp.code}")); return@flow }
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
        } catch (e: IOException) { emit(LlmEvent.Error("Network: ${e.message}", e)) }
          catch (e: Exception)   { emit(LlmEvent.Error("Error: ${e.message}", e)) }
    }.flowOn(Dispatchers.IO)

    private fun buildJson(): String {
        val msgs = JSONArray(); history.forEach { msgs.put(JSONObject(it)) }
        return JSONObject().apply {
            put("model", model); put("messages", msgs)
            put("stream", true); put("temperature", 0.7); put("max_tokens", 1024)
        }.toString()
    }

    private fun parseToken(json: String): String? = try {
        JSONObject(json).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("delta")
            ?.optString("content", null)
    } catch (_: Exception) { null }
}
