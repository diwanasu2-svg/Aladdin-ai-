package com.aladdin.engine.reasoning

import android.util.Log
import com.aladdin.engine.models.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 #7 — Dedicated Confidence Scorer.
 *
 * Aggregates multiple independent signals into a single, calibrated confidence
 * score for every AI answer. The score determines:
 *   - Whether Aladdin answers directly (high confidence)
 *   - Whether Aladdin hedges / qualifies (medium confidence)
 *   - Whether Aladdin asks a clarifying question (low confidence)
 *
 * Signals (weighted):
 *   1. Intent clarity    — how clearly the intent was classified          (25 %)
 *   2. Memory hits       — relevant memories found and used               (20 %)
 *   3. Tool results      — proportion of tools that returned real data    (20 %)
 *   4. LLM self-reported — confidence extracted from LLM response tag     (20 %)
 *   5. Answer completeness — heuristic based on response length/keywords  (15 %)
 */
@Singleton
class ConfidenceScorer @Inject constructor() {

    companion object {
        private const val TAG = "ConfidenceScorer"
        private const val CLARIFICATION_THRESHOLD = 0.50f
        private const val HIGH_CONFIDENCE         = 0.80f

        // Signal weights (sum = 1.00)
        private const val W_INTENT     = 0.25f
        private const val W_MEMORY     = 0.20f
        private const val W_TOOLS      = 0.20f
        private const val W_LLM        = 0.20f
        private const val W_COMPLETENESS = 0.15f
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Compute a full [ConfidenceScore] from all available signals.
     *
     * @param intent          Classified intent (type + raw confidence)
     * @param memoryContext   The memory context string that was injected (blank = no hits)
     * @param toolResults     Map of toolId → ToolResult for all tools executed
     * @param llmResponse     The raw LLM response text (may contain [confidence: X])
     * @param originalQuery   Original user query (used for keyword relevance check)
     */
    fun score(
        intent: ClassifiedIntent?,
        memoryContext: String,
        toolResults: Map<String, ToolResult> = emptyMap(),
        llmResponse: String = "",
        originalQuery: String = ""
    ): ConfidenceScore {
        val sIntent      = scoreIntent(intent)
        val sMemory      = scoreMemory(memoryContext)
        val sTools       = scoreTools(toolResults)
        val sLLM         = scoreLLMResponse(llmResponse)
        val sCompleteness = scoreCompleteness(llmResponse, originalQuery)

        val overall = (sIntent      * W_INTENT
                     + sMemory      * W_MEMORY
                     + sTools       * W_TOOLS
                     + sLLM        * W_LLM
                     + sCompleteness * W_COMPLETENESS).coerceIn(0f, 1f)

        val needsClarification = overall < CLARIFICATION_THRESHOLD
        val clarQuestion = if (needsClarification) buildClarificationQuestion(overall, intent) else null

        val breakdown = mapOf(
            "intent_clarity"    to sIntent,
            "memory_hits"       to sMemory,
            "tool_results"      to sTools,
            "llm_self_reported" to sLLM,
            "answer_completeness" to sCompleteness
        )

        Log.d(TAG, "Confidence: overall=${"%.2f".format(overall)} " +
              "intent=${"%.2f".format(sIntent)} mem=${"%.2f".format(sMemory)} " +
              "tools=${"%.2f".format(sTools)} llm=${"%.2f".format(sLLM)} " +
              "completeness=${"%.2f".format(sCompleteness)}")

        return ConfidenceScore(
            overall               = overall,
            signalBreakdown       = breakdown,
            needsClarification    = needsClarification,
            clarificationQuestion = clarQuestion
        )
    }

    /**
     * Quick version — uses only intent + response without tool data.
     * Use on the fast path where full tool results are not yet available.
     */
    fun quickScore(intent: ClassifiedIntent?, response: String, query: String): ConfidenceScore {
        return score(
            intent        = intent,
            memoryContext = "",
            toolResults   = emptyMap(),
            llmResponse   = response,
            originalQuery = query
        )
    }

    /**
     * Returns a formatted human-readable confidence label.
     */
    fun label(score: Float): String = when {
        score >= HIGH_CONFIDENCE         -> "High"
        score >= CLARIFICATION_THRESHOLD -> "Medium"
        else                             -> "Low"
    }

    /**
     * Decide response style based on confidence:
     *   - HIGH   → answer directly
     *   - MEDIUM → answer with a light qualifier
     *   - LOW    → ask for clarification before answering
     */
    fun decideResponseMode(score: ConfidenceScore): ResponseMode = when {
        score.needsClarification -> ResponseMode.ASK_CLARIFICATION
        score.overall >= HIGH_CONFIDENCE -> ResponseMode.DIRECT_ANSWER
        else -> ResponseMode.ANSWER_WITH_QUALIFIER
    }

    // ─── Signal Scorers ───────────────────────────────────────────────────────

    /** Score 1: intent clarity — based on classifier confidence + type specificity. */
    private fun scoreIntent(intent: ClassifiedIntent?): Float {
        if (intent == null) return 0.30f
        var s = intent.confidence
        // UNKNOWN intent is a strong negative signal
        if (intent.type == IntentType.UNKNOWN) s -= 0.20f
        // Having extracted entities is a positive signal
        if (intent.entities.isNotEmpty()) s += 0.05f
        return s.coerceIn(0f, 1f)
    }

    /** Score 2: memory hits — did we find relevant long-term context? */
    private fun scoreMemory(memoryContext: String): Float {
        if (memoryContext.isBlank()) return 0.40f   // neutral — not a negative
        val words = memoryContext.split("\\s+".toRegex()).size
        return when {
            words < 5  -> 0.45f
            words < 30 -> 0.70f
            words < 80 -> 0.85f
            else       -> 0.90f
        }
    }

    /** Score 3: tool results — fraction of tool calls that succeeded with real data. */
    private fun scoreTools(toolResults: Map<String, ToolResult>): Float {
        if (toolResults.isEmpty()) return 0.60f    // neutral — no tools needed
        val total     = toolResults.size.toFloat()
        val succeeded = toolResults.values.count { it.success && !it.isRecovered }.toFloat()
        val recovered = toolResults.values.count { it.isRecovered }.toFloat()
        // Full success = 1.0, recovered/partial = 0.5 credit, failed = 0
        val score = (succeeded + recovered * 0.5f) / total
        return score.coerceIn(0f, 1f)
    }

    /** Score 4: parse [confidence: X] tag from the LLM response. */
    private fun scoreLLMResponse(response: String): Float {
        if (response.isBlank()) return 0.50f
        val regex = Regex("\\[confidence:\\s*([0-9.]+)\\]", RegexOption.IGNORE_CASE)
        val parsed = regex.find(response)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        return parsed?.coerceIn(0f, 1f) ?: 0.65f   // default assumption if no tag
    }

    /** Score 5: heuristic answer completeness from length and keyword coverage. */
    private fun scoreCompleteness(response: String, query: String): Float {
        if (response.isBlank()) return 0f
        var s = 0.50f
        val words = response.split("\\s+".toRegex()).size
        s += when {
            words < 3   -> -0.30f
            words < 10  ->  0f
            words < 40  ->  0.20f
            words < 120 ->  0.30f
            else        ->  0.20f   // very long answers can be over-verbose
        }
        if (query.isNotBlank()) {
            val qWords = query.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }
            val hits   = qWords.count { response.lowercase().contains(it) }
            if (qWords.isNotEmpty()) s += (hits.toFloat() / qWords.size) * 0.25f
        }
        if (response.contains("[stub]", ignoreCase = true)) s -= 0.30f
        if (response.contains("i don't know", ignoreCase = true) &&
            response.contains("i cannot", ignoreCase = true)) s -= 0.15f
        return s.coerceIn(0f, 1f)
    }

    // ─── Clarification Question Builder ───────────────────────────────────────

    private fun buildClarificationQuestion(score: Float, intent: ClassifiedIntent?): String {
        return when {
            intent?.type == IntentType.UNKNOWN ->
                "I want to make sure I help you correctly — could you tell me more about what you need?"
            score < 0.30f ->
                "I'm not quite sure what you mean. Could you rephrase or give me more detail?"
            intent?.type in listOf(IntentType.RECALL_MEMORY, IntentType.FACTUAL_LOOKUP) ->
                "Could you be more specific about what you're looking for? For example, a date, name, or topic?"
            else ->
                "Just to make sure I understand — could you clarify what you'd like me to do?"
        }
    }
}

/** Describes how the AI should present its response given the confidence level. */
enum class ResponseMode {
    DIRECT_ANSWER,        // answer directly and confidently
    ANSWER_WITH_QUALIFIER, // answer but note uncertainty
    ASK_CLARIFICATION     // ask user to clarify before answering
}
