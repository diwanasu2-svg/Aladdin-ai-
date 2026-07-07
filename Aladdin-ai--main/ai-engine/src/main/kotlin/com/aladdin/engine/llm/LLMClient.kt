package com.aladdin.engine.llm

import android.content.Context
import android.util.Log
import com.aladdin.engine.models.AIEngineConfig
import com.aladdin.engine.models.ConversationMessage
import com.aladdin.engine.models.LLMProvider
import com.aladdin.llamacpp.LlamaCppEngine
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Unified LLM client supporting:
 *   - Google Gemini (via generativeai SDK)
 *   - Local Ollama (HTTP, compatible with OpenAI API format)
 *   - Stub (for testing / offline mode)
 *
 * Features:
 *   - Streaming and non-streaming completion
 *   - Automatic retry with exponential backoff on rate limits
 *   - Provider hot-swap at runtime
 *   - Token count estimation
 */
@Singleton
class LLMClient @Inject constructor(
    private val context: Context,
    private var config: AIEngineConfig
) {
    companion object {
        private const val TAG = "LLMClient"
        private const val BASE_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 32_000L
        private const val MAX_RETRIES = 4
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var geminiModel: GenerativeModel? = null

    // ─── On-device offline inference (llama.cpp + local GGUF model) ─────────
    // Lazily created; loading the model happens once via ensureLlamaCppReady().
    private val llamaCpp: LlamaCppEngine by lazy { LlamaCppEngine(context) }
    @Volatile private var llamaCppLoadAttempted = false

    private suspend fun ensureLlamaCppReady(): Boolean {
        if (llamaCpp.isReady) return true
        if (llamaCppLoadAttempted) return llamaCpp.isReady
        llamaCppLoadAttempted = true
        val modelPath = config.llamaCppModelPath.ifBlank { llamaCpp.defaultModelPath() }
        return llamaCpp.init(
            modelPath = modelPath,
            contextSize = config.llamaCppContextSize,
            threads = config.llamaCppThreads
        )
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    fun init(newConfig: AIEngineConfig = config) {
        config = newConfig
        if (config.llmProvider == LLMProvider.GEMINI && config.geminiApiKey.isNotBlank()) {
            geminiModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = config.geminiApiKey,
                generationConfig = generationConfig {
                    temperature = config.temperature
                    maxOutputTokens = 2048
                }
            )
            Log.i(TAG, "Gemini model initialized")
        } else if (config.llmProvider == LLMProvider.LLAMACPP) {
            // Model load is deferred (suspend) — triggered lazily on first
            // complete()/chat()/stream() call via ensureLlamaCppReady().
            llamaCppLoadAttempted = false
            Log.i(TAG, "LLM provider: LLAMACPP (on-device, offline, no Ollama needed)")
        } else {
            Log.i(TAG, "LLM provider: ${config.llmProvider}")
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Single-turn completion. */
    suspend fun complete(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt()
    ): String = withRetry {
        when (config.llmProvider) {
            LLMProvider.LLAMACPP -> completeLlamaCpp(prompt, systemPrompt)
            LLMProvider.GEMINI -> completeGemini(prompt, systemPrompt)
            LLMProvider.OLLAMA -> completeOllama(prompt, systemPrompt)
            LLMProvider.STUB   -> completeStub(prompt)
        }
    }

    /** Multi-turn chat completion from conversation history. */
    suspend fun chat(
        messages: List<ConversationMessage>,
        systemPrompt: String = defaultSystemPrompt()
    ): String = withRetry {
        when (config.llmProvider) {
            LLMProvider.LLAMACPP -> chatLlamaCpp(messages, systemPrompt)
            LLMProvider.GEMINI -> chatGemini(messages, systemPrompt)
            LLMProvider.OLLAMA -> chatOllama(messages, systemPrompt)
            LLMProvider.STUB   -> chatStub(messages)
        }
    }

    /** Streaming completion — emits tokens as they arrive. */
    fun stream(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt()
    ): Flow<String> = flow {
        when (config.llmProvider) {
            LLMProvider.LLAMACPP -> {
                if (!ensureLlamaCppReady()) { emit(completeStub(prompt)); return@flow }
                llamaCpp.completeStreaming(
                    buildLlamaCppPrompt(systemPrompt, prompt),
                    maxTokens = config.llamaCppMaxTokens
                ).collect { token -> emit(token) }
            }
            LLMProvider.GEMINI -> {
                val model = geminiModel ?: run { emit(completeStub(prompt)); return@flow }
                val response = model.generateContentStream(
                    content { text("$systemPrompt\n\n$prompt") }
                )
                response.collect { chunk ->
                    chunk.text?.let { emit(it) }
                }
            }
            LLMProvider.OLLAMA -> {
                // Ollama streaming via SSE
                streamOllama(prompt, systemPrompt).collect { emit(it) }
            }
            LLMProvider.STUB -> {
                val words = completeStub(prompt).split(" ")
                words.forEach { word -> emit("$word "); delay(30) }
            }
        }
    }

    /** Classify intent using LLM. Returns JSON string. */
    suspend fun classifyIntent(query: String): String = withRetry {
        val prompt = """
            Classify the user's intent. Respond with ONLY valid JSON, no markdown:
            {"intent":"INTENT_TYPE","confidence":0.95,"entities":{"key":"value"}}
            
            Intent types: QUESTION_ANSWERING, FACTUAL_LOOKUP, WEATHER_QUERY, NEWS_QUERY,
            SET_REMINDER, SEND_MESSAGE, PLAY_MUSIC, SEARCH_WEB, OPEN_APP, NAVIGATE,
            REMEMBER_FACT, RECALL_MEMORY, CREATE_PLAN, TRACK_GOAL, UPDATE_PROJECT,
            SMALL_TALK, CLARIFICATION_REQUEST, UNKNOWN
            
            User query: "$query"
        """.trimIndent()
        complete(prompt, "You are an intent classification system. Output only JSON.")
    }

    fun updateConfig(newConfig: AIEngineConfig) {
        config = newConfig
        init(newConfig)
    }

    fun estimateTokens(text: String): Int = (text.split("\\s+".toRegex()).size * 1.35).toInt()

    /** Releases the native llama.cpp context (call from AIEngine/Application teardown). */
    fun shutdown() {
        if (llamaCpp.isReady) llamaCpp.shutdown()
    }

    // ─── llama.cpp (on-device, offline) ─────────────────────────────────────

    private suspend fun completeLlamaCpp(prompt: String, systemPrompt: String): String {
        if (!ensureLlamaCppReady()) return completeStub(prompt)
        return llamaCpp.complete(buildLlamaCppPrompt(systemPrompt, prompt), config.llamaCppMaxTokens)
    }

    private suspend fun chatLlamaCpp(messages: List<ConversationMessage>, systemPrompt: String): String {
        if (!ensureLlamaCppReady()) return chatStub(messages)
        val history = messages.joinToString("\n") { m ->
            val role = when (m.role) {
                com.aladdin.engine.models.MessageRole.USER -> "User"
                com.aladdin.engine.models.MessageRole.ASSISTANT -> "Assistant"
                else -> "System"
            }
            "$role: ${m.content}"
        }
        val prompt = "$history\nAssistant:"
        return llamaCpp.complete(buildLlamaCppPrompt(systemPrompt, prompt), config.llamaCppMaxTokens)
    }

    /** Gemma/Llama-style instruction prompt wrapper for local GGUF completion. */
    private fun buildLlamaCppPrompt(systemPrompt: String, userPrompt: String): String =
        "<start_of_turn>system\n$systemPrompt<end_of_turn>\n" +
        "<start_of_turn>user\n$userPrompt<end_of_turn>\n" +
        "<start_of_turn>model\n"

    // ─── Gemini ───────────────────────────────────────────────────────────────

    private suspend fun completeGemini(prompt: String, systemPrompt: String): String {
        val model = geminiModel ?: return completeStub(prompt)
        val response: GenerateContentResponse = model.generateContent(
            content { text("$systemPrompt\n\n$prompt") }
        )
        return response.text ?: "(empty response)"
    }

    private suspend fun chatGemini(messages: List<ConversationMessage>, systemPrompt: String): String {
        val model = geminiModel ?: return chatStub(messages)
        val chat = model.startChat()
        // Send all messages except the last as history
        messages.dropLast(1).forEach { msg ->
            when (msg.role) {
                com.aladdin.engine.models.MessageRole.USER ->
                    chat.sendMessage(content("user") { text(msg.content) })
                com.aladdin.engine.models.MessageRole.ASSISTANT ->
                    chat.sendMessage(content("model") { text(msg.content) })
                else -> {}
            }
        }
        val lastMessage = messages.lastOrNull()?.content ?: return "(empty)"
        val response = chat.sendMessage(lastMessage)
        return response.text ?: "(empty)"
    }

    // ─── Ollama ───────────────────────────────────────────────────────────────

    private suspend fun completeOllama(prompt: String, systemPrompt: String): String =
        withContext(Dispatchers.IO) {
            val json = buildOllamaBody(systemPrompt, prompt, stream = false)
            val request = Request.Builder()
                .url("${config.ollamaBaseUrl}/api/generate")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Ollama error ${response.code}")
                val body = response.body?.string() ?: throw Exception("Empty Ollama response")
                extractOllamaResponse(body)
            }
        }

    private suspend fun chatOllama(messages: List<ConversationMessage>, systemPrompt: String): String =
        withContext(Dispatchers.IO) {
            val messagesJson = buildChatMessagesJson(messages, systemPrompt)
            val json = """{"model":"${config.ollamaModel}","messages":$messagesJson,"stream":false}"""
            val request = Request.Builder()
                .url("${config.ollamaBaseUrl}/api/chat")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Ollama chat error ${response.code}")
                val body = response.body?.string() ?: ""
                extractOllamaChatResponse(body)
            }
        }

    private fun streamOllama(prompt: String, systemPrompt: String): Flow<String> = flow {
        withContext(Dispatchers.IO) {
            val json = buildOllamaBody(systemPrompt, prompt, stream = true)
            val request = Request.Builder()
                .url("${config.ollamaBaseUrl}/api/generate")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.body?.source()?.let { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val token = extractOllamaToken(line)
                        if (token.isNotEmpty()) emit(token)
                    }
                }
            }
        }
    }

    // ─── Stub ─────────────────────────────────────────────────────────────────

    private fun completeStub(prompt: String): String {
        val queryStart = prompt.takeLast(200).lowercase()
        return when {
            "weather" in queryStart -> "It's currently sunny with a temperature of 22°C. A pleasant day!"
            "remind" in queryStart -> "I've set a reminder for you. I'll make sure to notify you at the specified time."
            "play" in queryStart  -> "Starting playback now. Enjoy the music!"
            "navigate" in queryStart || "directions" in queryStart -> "Navigation started. Turn right in 200 meters."
            "news" in queryStart  -> "Top story: Technology continues to advance rapidly, with AI making breakthroughs daily."
            "search" in queryStart -> "Here are the top results for your search query..."
            "hello" in queryStart || "hi " in queryStart -> "Hello! I'm Aladdin, your AI assistant. How can I help you today?"
            "remember" in queryStart -> "I've stored that information in my long-term memory. I'll remember it for future conversations."
            else -> "I understand your request. Let me help you with that."
        }
    }

    private fun chatStub(messages: List<ConversationMessage>): String {
        val lastUser = messages.lastOrNull { it.role == com.aladdin.engine.models.MessageRole.USER }?.content ?: ""
        return completeStub(lastUser)
    }

    // ─── Retry Logic ──────────────────────────────────────────────────────────

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                val isRateLimit = e.message?.contains("429") == true || e.message?.contains("quota") == true
                val delayMs = min(
                    (BASE_RETRY_DELAY_MS * 2.0.pow(attempt)).toLong(),
                    MAX_RETRY_DELAY_MS
                )
                Log.w(TAG, "LLM attempt ${attempt + 1}/${MAX_RETRIES + 1} failed (rateLimit=$isRateLimit): ${e.message}. Retrying in ${delayMs}ms")
                if (attempt < MAX_RETRIES) delay(delayMs)
            }
        }
        throw lastException ?: Exception("LLM failed after $MAX_RETRIES retries")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun defaultSystemPrompt(): String = """
        You are Aladdin, an intelligent personal AI assistant. 
        You are helpful, accurate, and concise. 
        You have access to the user's memory, calendar, and contacts.
        Always respond in a natural, conversational tone.
    """.trimIndent()

    private fun buildOllamaBody(system: String, prompt: String, stream: Boolean): String {
        val escapedSystem = escapeJson(system)
        val escapedPrompt = escapeJson(prompt)
        return """{"model":"${config.ollamaModel}","system":"$escapedSystem","prompt":"$escapedPrompt","stream":$stream,"options":{"temperature":${config.temperature}}}"""
    }

    private fun buildChatMessagesJson(messages: List<ConversationMessage>, system: String): String {
        val sb = StringBuilder("[")
        sb.append("""{"role":"system","content":"${escapeJson(system)}"}""")
        messages.forEach { m ->
            val role = when (m.role) {
                com.aladdin.engine.models.MessageRole.USER -> "user"
                com.aladdin.engine.models.MessageRole.ASSISTANT -> "assistant"
                else -> "user"
            }
            sb.append(""",{"role":"$role","content":"${escapeJson(m.content)}"}""")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun extractOllamaResponse(body: String): String {
        val p = Regex("\"response\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return p.find(body)?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: body
    }

    private fun extractOllamaChatResponse(body: String): String {
        val p = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return p.find(body)?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: body
    }

    private fun extractOllamaToken(line: String): String {
        val p = Regex("\"response\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return p.find(line)?.groupValues?.getOrNull(1)?.replace("\\n", "\n") ?: ""
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
