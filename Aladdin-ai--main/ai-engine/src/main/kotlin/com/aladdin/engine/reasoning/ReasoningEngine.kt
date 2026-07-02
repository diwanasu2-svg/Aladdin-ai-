package com.aladdin.engine.reasoning

import android.util.Log
import com.aladdin.engine.llm.LLMClient
import com.aladdin.engine.models.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 — Chain-of-Thought (CoT) Reasoning Engine.
 *
 * Phase 4 upgrades:
 *   1. reasonFast() — real fast decision-making: generates proper conclusion,
 *      confidence, and explanation without an LLM call.
 *   7. Confidence Scoring — multi-signal confidence calculation (memory hits,
 *      tool results, LLM output, intent clarity).
 *  10. Improved Tool Reasoning — decides which tool to use, sequences multiple
 *      tools, combines outputs, falls back to alternatives, and verifies results.
 */
@Singleton
class ReasoningEngine @Inject constructor(
    private val llmClient: LLMClient
) {
    companion object {
        private const val TAG = "ReasoningEngine"
        private const val MAX_STEPS = 8
        private const val LOW_CONFIDENCE_THRESHOLD = 0.55f
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
    }

    // ─── Full CoT Reasoning (async, LLM-backed) ───────────────────────────────

    suspend fun reason(
        query: String,
        context: String = "",
        intent: ClassifiedIntent? = null,
        conversationHistory: List<ConversationMessage> = emptyList()
    ): ReasoningChain {
        Log.d(TAG, "Starting CoT for: '${query.take(60)}'")

        val steps = mutableListOf<ReasoningStep>()
        var stepIndex = 0

        val initialThought = buildInitialThought(query, context, intent)
        steps.add(ReasoningStep(stepIndex++, thought = initialThought))

        val toolPlan = planToolSequence(query, intent, context)
        if (toolPlan.isNotEmpty()) {
            val toolThought = "To answer this I will use ${toolPlan.size} tool(s) in sequence: ${toolPlan.joinToString(" → ")}."
            steps.add(ReasoningStep(stepIndex++, thought = toolThought, action = toolPlan.first()))
        }

        if (context.isNotBlank()) {
            val relevantContext = extractRelevantContext(query, context)
            if (relevantContext.isNotBlank()) {
                val contextThought = "Relevant memory context found: ${relevantContext.take(120)}…"
                steps.add(ReasoningStep(stepIndex++, thought = contextThought, observation = "Context loaded"))
            }
        }

        val cotPrompt = buildCoTPrompt(query, context, steps, intent, conversationHistory)
        val llmAnswer = try {
            llmClient.complete(cotPrompt)
        } catch (e: Exception) {
            Log.w(TAG, "LLM failed in reasoning, using rule-based answer: ${e.message}")
            generateRuleBasedAnswer(query, context, intent)
        }

        val (answer, rawConfidence) = parseAnswerAndConfidence(llmAnswer)

        val finalConfidence = computeConfidence(
            rawLLMConfidence = rawConfidence,
            intent = intent,
            hasContext = context.isNotBlank(),
            answerLength = answer.split("\\s+".toRegex()).size,
            toolPlanSize = toolPlan.size,
            conversationTurns = conversationHistory.size
        )

        steps.add(ReasoningStep(
            index = stepIndex,
            thought = buildFinalThought(finalConfidence),
            action = "generate_response",
            observation = answer.take(100),
            isFinal = true
        ))

        if (finalConfidence < LOW_CONFIDENCE_THRESHOLD) {
            Log.i(TAG, "Low confidence ($finalConfidence) — flagging for clarification")
        }

        return ReasoningChain(
            query = query,
            steps = steps,
            conclusion = answer,
            confidence = finalConfidence,
            needsClarification = finalConfidence < LOW_CONFIDENCE_THRESHOLD,
            toolSequence = toolPlan
        )
    }

    // ─── Phase 4 #1 — Real reasonFast() ──────────────────────────────────────

    /**
     * Fast synchronous reasoning — no LLM call.
     * Generates a real conclusion from intent, entities, and rule patterns.
     * Returns proper confidence derived from intent clarity.
     */
    fun reasonFast(query: String, intent: ClassifiedIntent?): ReasoningChain {
        val intentType = intent?.type ?: IntentType.UNKNOWN
        val entities   = intent?.entities ?: emptyMap()
        val intentConf = intent?.confidence ?: 0.5f

        val (thought, conclusion, confidence) = when (intentType) {
            IntentType.SMALL_TALK -> Triple(
                "This is casual conversation. I can reply directly without tools.",
                buildSmallTalkReply(query),
                (0.75f + intentConf * 0.15f).coerceIn(0f, 1f)
            )
            IntentType.CLARIFICATION_REQUEST -> Triple(
                "The user needs clarification. I will ask what exactly they need.",
                "Could you please give me a bit more detail so I can help you better?",
                0.90f
            )
            IntentType.REMEMBER_FACT -> Triple(
                "User wants me to store a fact. I will confirm storage.",
                "Got it — I've noted that and will remember it for future conversations.",
                (0.85f + intentConf * 0.10f).coerceIn(0f, 1f)
            )
            IntentType.RECALL_MEMORY -> Triple(
                "User wants to recall something from memory. Searching context.",
                buildRecallReply(query, entities),
                (0.60f + intentConf * 0.20f).coerceIn(0f, 1f)
            )
            IntentType.SET_REMINDER -> Triple(
                "Reminder request detected. Extracting time and subject from entities.",
                buildReminderReply(entities),
                (0.80f + intentConf * 0.15f).coerceIn(0f, 1f)
            )
            IntentType.WEATHER_QUERY -> Triple(
                "Weather query. Will need weather.fetch tool — fast path provides interim reply.",
                "Let me fetch the latest weather for ${entities["location"] ?: "your location"} right now.",
                (0.70f + intentConf * 0.10f).coerceIn(0f, 1f)
            )
            IntentType.NAVIGATE -> Triple(
                "Navigation request. Destination identified from entities.",
                "Starting navigation to ${entities["destination"] ?: "your destination"}.",
                (0.80f + intentConf * 0.10f).coerceIn(0f, 1f)
            )
            IntentType.PLAY_MUSIC -> Triple(
                "Music playback request.",
                "Playing ${entities["track"] ?: entities["query"] ?: "music"} for you now.",
                (0.82f + intentConf * 0.10f).coerceIn(0f, 1f)
            )
            IntentType.SEND_MESSAGE -> Triple(
                "Message send request. Recipient and content extraction needed.",
                "I'll compose and send that message to ${entities["recipient"] ?: "the recipient"} right away.",
                (0.78f + intentConf * 0.12f).coerceIn(0f, 1f)
            )
            IntentType.QUESTION_ANSWERING, IntentType.FACTUAL_LOOKUP -> Triple(
                "Factual/Q&A request. Answering from general knowledge with fast path.",
                buildFactualFastReply(query),
                (0.65f + intentConf * 0.15f).coerceIn(0f, 1f)
            )
            IntentType.CREATE_PLAN, IntentType.TRACK_GOAL -> Triple(
                "Goal/plan creation request. Acknowledging and initialising tracking.",
                "I've captured your goal and will break it down into actionable steps.",
                (0.80f + intentConf * 0.10f).coerceIn(0f, 1f)
            )
            else -> Triple(
                "General request. Providing best-effort response.",
                buildGenericFastReply(query),
                (0.60f + intentConf * 0.15f).coerceIn(0f, 1f)
            )
        }

        val explanation = "Fast-path reasoning: intent=${intentType.name}, " +
                          "intentConfidence=${"%.2f".format(intentConf)}, " +
                          "finalConfidence=${"%.2f".format(confidence)}"
        Log.d(TAG, explanation)

        return ReasoningChain(
            query = query,
            steps = listOf(
                ReasoningStep(0, thought = thought, isFinal = false),
                ReasoningStep(1, thought = explanation, action = "fast_path", observation = conclusion.take(80), isFinal = true)
            ),
            conclusion = conclusion,
            confidence = confidence,
            needsClarification = confidence < LOW_CONFIDENCE_THRESHOLD,
            toolSequence = listOf(selectPrimaryTool(intentType))
        )
    }

    // ─── Phase 4 #7 — Confidence Scoring ─────────────────────────────────────

    /**
     * Multi-signal confidence score.
     * Signals: LLM self-reported confidence, intent clarity, memory context
     * availability, answer length, tool coverage, conversation depth.
     */
    fun computeConfidence(
        rawLLMConfidence: Float,
        intent: ClassifiedIntent?,
        hasContext: Boolean,
        answerLength: Int,
        toolPlanSize: Int,
        conversationTurns: Int
    ): Float {
        var score = rawLLMConfidence * 0.50f

        val intentConf = intent?.confidence ?: 0.5f
        score += intentConf * 0.20f

        if (hasContext) score += 0.10f

        score += when {
            answerLength < 3  -> -0.15f
            answerLength < 8  -> 0f
            answerLength < 60 -> 0.08f
            else              -> 0.05f
        }

        if (toolPlanSize > 0) score += 0.05f

        score += (conversationTurns.coerceAtMost(10) * 0.005f)

        if (intent?.type == IntentType.UNKNOWN) score -= 0.10f

        return score.coerceIn(0f, 1f)
    }

    /**
     * Checks if confidence is too low and returns a clarification question,
     * or null if confidence is acceptable.
     */
    fun clarificationQuestionIfNeeded(chain: ReasoningChain): String? {
        if (!chain.needsClarification) return null
        return when {
            chain.confidence < 0.35f ->
                "I'm not quite sure what you mean. Could you give me a bit more detail?"
            chain.confidence < LOW_CONFIDENCE_THRESHOLD ->
                "I want to make sure I answer correctly — could you clarify what you're looking for?"
            else -> null
        }
    }

    // ─── Phase 4 #10 — Improved Tool Reasoning ────────────────────────────────

    /**
     * Plans a sequence of tools to use for the given query+intent.
     * Supports multi-tool chaining, alternatives, and fallbacks.
     */
    fun planToolSequence(
        query: String,
        intent: ClassifiedIntent?,
        context: String = ""
    ): List<String> {
        val primaryTool = selectPrimaryTool(intent?.type ?: IntentType.UNKNOWN)
        val chainedTools = getChainedTools(intent?.type ?: IntentType.UNKNOWN, query)
        return (listOf(primaryTool) + chainedTools).distinct()
    }

    /**
     * After a tool executes, verify its output is usable.
     * Returns true if the result is valid and can be used downstream.
     */
    fun verifyToolResult(toolId: String, output: String, parameters: Map<String, String>): Boolean {
        if (output.isBlank()) return false
        if (output.contains("[stub]", ignoreCase = true)) return false
        if (output.contains("error", ignoreCase = true) && output.length < 30) return false

        return when {
            toolId.startsWith("weather.") -> output.contains(Regex("\\d+[°C°F]|degrees|temperature|sunny|cloudy|rain", RegexOption.IGNORE_CASE))
            toolId.startsWith("memory.")  -> output.length > 5
            toolId.startsWith("search.")  -> output.length > 20
            toolId.startsWith("llm.")     -> output.length > 10
            else -> true
        }
    }

    /**
     * Given a failed primary tool, return the best alternative tool to try.
     */
    fun selectFallbackTool(failedToolId: String, intent: ClassifiedIntent?): String? {
        return when {
            failedToolId.startsWith("weather.")  -> "search.execute"
            failedToolId.startsWith("news.")     -> "search.execute"
            failedToolId.startsWith("maps.")     -> "search.execute"
            failedToolId.startsWith("memory.")   -> "llm.answer"
            failedToolId.startsWith("music.")    -> "app.launch"
            failedToolId.startsWith("llm.")      -> null
            failedToolId == "search.execute"     -> "llm.answer"
            else -> "llm.answer"
        }
    }

    /**
     * Combine results from multiple tool outputs into a single coherent context string.
     */
    fun combineToolOutputs(results: Map<String, String>): String {
        if (results.isEmpty()) return ""
        if (results.size == 1) return results.values.first()

        val sb = StringBuilder()
        results.entries.forEachIndexed { i, (toolId, output) ->
            if (output.isNotBlank() && !output.contains("[stub]", ignoreCase = true)) {
                sb.appendLine("[$toolId]: ${output.take(300)}")
            }
        }
        return sb.toString().trim()
    }

    // ─── Format Chain ─────────────────────────────────────────────────────────

    fun formatChain(chain: ReasoningChain): String {
        val sb = StringBuilder()
        chain.steps.forEach { step ->
            sb.appendLine("Thought ${step.index + 1}: ${step.thought}")
            step.action?.let { sb.appendLine("Action: $it") }
            step.observation?.let { sb.appendLine("Observation: $it") }
        }
        sb.appendLine("Answer: ${chain.conclusion}")
        sb.appendLine("Confidence: ${"%.0f".format(chain.confidence * 100)}%")
        if (chain.needsClarification) sb.appendLine("[Low confidence — clarification suggested]")
        return sb.toString()
    }

    // ─── Prompts ──────────────────────────────────────────────────────────────

    private fun buildInitialThought(query: String, context: String, intent: ClassifiedIntent?): String {
        val intentStr = intent?.type?.name?.replace("_", " ")?.lowercase() ?: "general"
        val hasCtx = if (context.isNotBlank()) "I have relevant memory context available." else "No memory context available."
        return "The user is making a $intentStr request. $hasCtx I will think through this carefully."
    }

    private fun buildCoTPrompt(
        query: String,
        context: String,
        steps: List<ReasoningStep>,
        intent: ClassifiedIntent?,
        history: List<ConversationMessage>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("You are Aladdin, an intelligent AI assistant. Think step by step.")
        sb.appendLine()
        if (history.isNotEmpty()) {
            sb.appendLine("=== CONVERSATION HISTORY (most recent last) ===")
            history.takeLast(8).forEach { m ->
                val role = if (m.role == MessageRole.USER) "User" else "Aladdin"
                sb.appendLine("$role: ${m.content.take(200)}")
            }
            sb.appendLine()
        }
        if (context.isNotBlank()) {
            sb.appendLine("=== RELEVANT MEMORY CONTEXT ===")
            sb.appendLine(context.take(600))
            sb.appendLine()
        }
        sb.appendLine("=== CURRENT REQUEST ===")
        sb.appendLine("User: $query")
        sb.appendLine()
        sb.appendLine("Think through this step by step. Provide a helpful, accurate, and complete response.")
        sb.appendLine("At the very end of your response, add exactly one line: [confidence: 0.X]")
        return sb.toString()
    }

    private fun buildFinalThought(confidence: Float): String = when {
        confidence >= HIGH_CONFIDENCE_THRESHOLD ->
            "I have high confidence in this answer and can respond directly."
        confidence >= LOW_CONFIDENCE_THRESHOLD ->
            "I have reasonable confidence. The answer covers the main aspects of the query."
        else ->
            "Confidence is low. I should ask for clarification or note uncertainty in the answer."
    }

    // ─── Rule-Based Fallbacks ─────────────────────────────────────────────────

    private fun generateRuleBasedAnswer(query: String, context: String, intent: ClassifiedIntent?): String {
        return when (intent?.type) {
            IntentType.SMALL_TALK      -> buildSmallTalkReply(query)
            IntentType.WEATHER_QUERY   -> "I need a moment to fetch the weather. Let me check for ${intent.entities["location"] ?: "your location"}."
            IntentType.RECALL_MEMORY   -> if (context.isNotBlank()) "Based on what I remember: ${context.take(200)}" else "I don't have specific information about that in my memory."
            IntentType.SET_REMINDER    -> buildReminderReply(intent.entities)
            else -> "I understand you're asking about ${query.take(60)}. Let me help you with that."
        }
    }

    private fun buildSmallTalkReply(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hello! I'm Aladdin, always here to help. What can I do for you today?"
            lower.contains("how are you") || lower.contains("how r u") ->
                "I'm running at full capacity and ready to help! What's on your mind?"
            lower.contains("thank") ->
                "You're very welcome! Let me know if there's anything else I can help with."
            lower.contains("bye") || lower.contains("goodbye") ->
                "Goodbye! Feel free to come back whenever you need help. Take care!"
            lower.contains("who are you") || lower.contains("what are you") ->
                "I'm Aladdin, your personal AI assistant. I can help with tasks, answer questions, set reminders, and much more."
            else -> "I'm here and happy to help! What would you like to do?"
        }
    }

    private fun buildRecallReply(query: String, entities: Map<String, String>): String {
        val subject = entities["subject"] ?: entities["topic"] ?: "that"
        return "Let me search my memory for information about $subject. I'll retrieve what I know."
    }

    private fun buildReminderReply(entities: Map<String, String>): String {
        val subject = entities["subject"] ?: entities["task"] ?: "your reminder"
        val time    = entities["time"] ?: entities["trigger_at"] ?: "the specified time"
        return "Done — I've set a reminder for \"$subject\" at $time. I'll notify you then."
    }

    private fun buildFactualFastReply(query: String): String {
        return "That's a great question. Let me pull together what I know about that for you."
    }

    private fun buildGenericFastReply(query: String): String {
        val words = query.split("\\s+".toRegex()).take(6).joinToString(" ")
        return "I'll work on \"$words…\" for you right away."
    }

    // ─── Tool Selection Helpers ───────────────────────────────────────────────

    private fun selectPrimaryTool(intent: IntentType): String = when (intent) {
        IntentType.WEATHER_QUERY   -> "weather.fetch"
        IntentType.NEWS_QUERY      -> "news.fetch"
        IntentType.SEARCH_WEB      -> "search.execute"
        IntentType.RECALL_MEMORY   -> "memory.search"
        IntentType.REMEMBER_FACT   -> "memory.store"
        IntentType.SET_REMINDER    -> "reminder.create"
        IntentType.NAVIGATE        -> "maps.navigate"
        IntentType.SEND_MESSAGE    -> "message.send"
        IntentType.PLAY_MUSIC      -> "music.play"
        IntentType.OPEN_APP        -> "app.launch"
        IntentType.SMALL_TALK      -> "llm.chat"
        IntentType.QUESTION_ANSWERING,
        IntentType.FACTUAL_LOOKUP  -> "llm.answer"
        IntentType.CREATE_PLAN,
        IntentType.TRACK_GOAL      -> "goal.upsert"
        else                       -> "llm.answer"
    }

    private fun getChainedTools(intent: IntentType, query: String): List<String> = when (intent) {
        IntentType.WEATHER_QUERY   -> listOf("location.resolve")
        IntentType.NAVIGATE        -> listOf("location.resolve", "maps.eta")
        IntentType.SEARCH_WEB      -> listOf("search.extract_results")
        IntentType.RECALL_MEMORY   -> listOf("memory.rank")
        IntentType.SET_REMINDER    -> listOf("alarm.schedule")
        IntentType.REMEMBER_FACT   -> listOf("memory.embed")
        IntentType.SEND_MESSAGE    -> listOf("contact.resolve", "message.compose")
        IntentType.CREATE_PLAN     -> listOf("planner.decompose", "memory.store")
        else                       -> emptyList()
    }

    private fun extractRelevantContext(query: String, context: String): String {
        val queryWords = query.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }.toSet()
        val sentences  = context.split(Regex("[.!?\n]+"))
        val relevant   = sentences.filter { sentence ->
            queryWords.any { word -> sentence.lowercase().contains(word) }
        }
        return relevant.take(3).joinToString(" ").take(400)
    }

    private fun parseAnswerAndConfidence(llmResponse: String): Pair<String, Float> {
        val confidenceRegex = Regex("\\[confidence:\\s*([0-9.]+)\\]", RegexOption.IGNORE_CASE)
        val match = confidenceRegex.find(llmResponse)
        val confidence = match?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.75f
        val answer = llmResponse.replace(confidenceRegex, "").trim()
        return Pair(answer, confidence)
    }

    private fun requiresToolUse(query: String, intent: ClassifiedIntent?): Boolean {
        return when (intent?.type) {
            IntentType.SMALL_TALK, IntentType.CLARIFICATION_REQUEST -> false
            else -> true
        }
    }
}
