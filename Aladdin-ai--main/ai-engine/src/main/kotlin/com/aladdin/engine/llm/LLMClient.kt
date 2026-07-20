package com.aladdin.engine.llm

import android.util.Log
import com.aladdin.engine.models.AIEngineConfig
import com.aladdin.engine.models.ConversationMessage
import com.aladdin.engine.models.LLMProvider
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Unified LLM client — Gemini only (Ollama removed).
 *
 * Providers:
 *   - GEMINI  — Google Generative AI SDK (default)
 *   - STUB    — offline/test responses, no network
 */
@Singleton
class LLMClient @Inject constructor(
    private var config: AIEngineConfig
) {
    companion object {
        private const val TAG = "LLMClient"
        private const val MAX_RETRIES = 4
        private const val BASE_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS  = 32_000L
    }

    private var geminiModel: GenerativeModel? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(newConfig: AIEngineConfig = config) {
        config = newConfig
        if (config.llmProvider == LLMProvider.GEMINI && config.geminiApiKey.isNotBlank()) {
            geminiModel = GenerativeModel(
                modelName = config.geminiModel,
                apiKey    = config.geminiApiKey,
                generationConfig = generationConfig {
                    temperature    = config.temperature
                    maxOutputTokens = 2048
                }
            )
            Log.i(TAG, "Gemini model initialised (${config.geminiModel})")
        } else {
            Log.i(TAG, "LLM provider: ${config.llmProvider} — Gemini key blank, using stub")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun complete(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt()
    ): String = withRetry {
        when (config.llmProvider) {
            LLMProvider.GEMINI -> completeGemini(prompt, systemPrompt)
            LLMProvider.STUB   -> completeStub(prompt)
        }
    }

    suspend fun chat(
        messages: List<ConversationMessage>,
        systemPrompt: String = defaultSystemPrompt()
    ): String = withRetry {
        when (config.llmProvider) {
            LLMProvider.GEMINI -> chatGemini(messages, systemPrompt)
            LLMProvider.STUB   -> chatStub(messages)
        }
    }

    fun stream(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt()
    ): Flow<String> = flow {
        when (config.llmProvider) {
            LLMProvider.GEMINI -> {
                val model = geminiModel ?: run { emit(completeStub(prompt)); return@flow }
                model.generateContentStream(content { text("$systemPrompt\n\n$prompt") })
                    .collect { chunk -> chunk.text?.let { emit(it) } }
            }
            LLMProvider.STUB -> {
                completeStub(prompt).split(" ").forEach { word -> emit("$word "); delay(30) }
            }
        }
    }

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

    fun updateConfig(newConfig: AIEngineConfig) { config = newConfig; init(newConfig) }

    fun estimateTokens(text: String): Int = (text.split("\\s+".toRegex()).size * 1.35).toInt()

    // ── Gemini ────────────────────────────────────────────────────────────────

    private suspend fun completeGemini(prompt: String, systemPrompt: String): String {
        val model = geminiModel ?: return completeStub(prompt)
        val response: GenerateContentResponse =
            model.generateContent(content { text("$systemPrompt\n\n$prompt") })
        return response.text ?: "(empty response)"
    }

    private suspend fun chatGemini(messages: List<ConversationMessage>, systemPrompt: String): String {
        val model = geminiModel ?: return chatStub(messages)
        val chat  = model.startChat()
        messages.dropLast(1).forEach { msg ->
            when (msg.role) {
                com.aladdin.engine.models.MessageRole.USER ->
                    chat.sendMessage(content("user")  { text(msg.content) })
                com.aladdin.engine.models.MessageRole.ASSISTANT ->
                    chat.sendMessage(content("model") { text(msg.content) })
                else -> {}
            }
        }
        val last = messages.lastOrNull()?.content ?: return "(empty)"
        return chat.sendMessage(last).text ?: "(empty)"
    }

    // ── Stub ──────────────────────────────────────────────────────────────────

    private fun completeStub(prompt: String): String {
        val q = prompt.takeLast(200).lowercase()
        return when {
            "weather"   in q -> "It's sunny with a temperature of 22°C. A pleasant day!"
            "remind"    in q -> "Reminder set. I'll notify you at the specified time."
            "play"      in q -> "Starting playback now. Enjoy the music!"
            "navigate"  in q || "directions" in q -> "Navigation started. Turn right in 200 meters."
            "news"      in q -> "Top story: AI continues to advance rapidly."
            "search"    in q -> "Here are the top results for your query…"
            "hello"     in q || "hi " in q -> "Hello! I'm Aladdin, your AI assistant. How can I help?"
            "remember"  in q -> "Stored in long-term memory. I'll recall it next time."
            else -> "I understand your request. Let me help you with that."
        }
    }

    private fun chatStub(messages: List<ConversationMessage>): String =
        completeStub(messages.lastOrNull { it.role == com.aladdin.engine.models.MessageRole.USER }?.content ?: "")

    // ── Retry ─────────────────────────────────────────────────────────────────

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var last: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try { return block() } catch (e: Exception) {
                last = e
                val delayMs = min((BASE_RETRY_MS * 2.0.pow(attempt)).toLong(), MAX_RETRY_MS)
                Log.w(TAG, "LLM attempt ${attempt+1}/${MAX_RETRIES+1} failed: ${e.message}. Retry in ${delayMs}ms")
                if (attempt < MAX_RETRIES) delay(delayMs)
            }
        }
        throw last ?: Exception("LLM failed after $MAX_RETRIES retries")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun defaultSystemPrompt() = """
        You are Aladdin, an intelligent personal AI assistant.
        You are helpful, accurate, and concise.
        You have access to the user's memory, calendar, and contacts.
        Always respond in a natural, conversational tone.
    """.trimIndent()
}
