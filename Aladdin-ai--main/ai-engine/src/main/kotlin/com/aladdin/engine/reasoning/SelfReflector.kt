package com.aladdin.engine.reasoning

import android.util.Log
import com.aladdin.engine.llm.LLMClient
import com.aladdin.engine.models.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 — Self-Reflection and Auto-Correction Engine.
 *
 * Phase 4 upgrade #4 — Improved Reflection:
 *   - After every response, the AI evaluates its own output using the LLM.
 *   - Detects specific mistake categories (incomplete, contradictory, off-topic, etc.).
 *   - If the answer is weak, calls the LLM to generate an improved version.
 *   - Learned corrections are attached to the result for future pattern avoidance.
 *   - Tracks a short-term mistake log to avoid repeating the same errors.
 */
@Singleton
class SelfReflector @Inject constructor(
    private val llmClient: LLMClient
) {
    companion object {
        private const val TAG = "SelfReflector"
        private const val MIN_ACCEPTABLE_QUALITY = 0.60f
        private const val LLM_REFLECTION_THRESHOLD = 0.70f
        private const val MAX_MISTAKE_LOG = 20
    }

    /** Rolling log of detected mistakes — used to avoid repeating patterns. */
    private val mistakeLog = ArrayDeque<MistakeEntry>(MAX_MISTAKE_LOG)

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Reflect on a completed plan execution.
     * Phase 4: Uses LLM to evaluate quality and generate a real improved response.
     */
    suspend fun reflectOnPlan(
        plan: Plan,
        response: String,
        originalQuery: String
    ): ReflectionResult {
        Log.d(TAG, "Reflecting on plan: ${plan.id}")

        val failedTasks    = plan.tasks.filter { it.status == TaskStatus.FAILED }
        val completedTasks = plan.tasks.filter { it.status == TaskStatus.COMPLETED }
        val completionRate = if (plan.tasks.isEmpty()) 1f
            else completedTasks.size.toFloat() / plan.tasks.size.coerceAtLeast(1)

        val mistakeTypes   = detectMistakeTypes(response, originalQuery)
        val qualityScore   = evaluateResponseQuality(response, originalQuery, mistakeTypes)
        val goalAligned    = checkGoalAlignment(response, originalQuery)
        val corrections    = buildCorrections(failedTasks, qualityScore, goalAligned, response, originalQuery, mistakeTypes)

        val revisedText = if (corrections.isNotEmpty() && qualityScore < LLM_REFLECTION_THRESHOLD) {
            generateImprovedResponse(response, originalQuery, corrections)
        } else null

        if (corrections.isNotEmpty()) {
            recordMistakes(mistakeTypes, originalQuery)
        }

        val result = ReflectionResult(
            planId           = plan.id,
            qualityScore     = qualityScore,
            completionRate   = completionRate,
            isAcceptable     = qualityScore >= MIN_ACCEPTABLE_QUALITY && completionRate >= 0.7f,
            goalAligned      = goalAligned,
            failedTaskCount  = failedTasks.size,
            corrections      = corrections,
            revisedResponse  = revisedText,
            mistakeTypes     = mistakeTypes,
            improvementNote  = if (corrections.isNotEmpty()) summariseCorrections(corrections) else null
        )

        Log.i(TAG, "Reflection done: quality=${"%.2f".format(qualityScore)} " +
              "completion=${"%.2f".format(completionRate)} acceptable=${result.isAcceptable} " +
              "mistakes=${mistakeTypes.size}")
        return result
    }

    /**
     * Quick reflection on a raw LLM response without a plan.
     * Phase 4: detects mistake categories and improves if needed.
     */
    suspend fun reflectOnResponse(
        response: String,
        query: String,
        context: String = ""
    ): ReflectionResult {
        val mistakeTypes = detectMistakeTypes(response, query, context)
        val quality      = evaluateResponseQuality(response, query, mistakeTypes)
        val aligned      = checkGoalAlignment(response, query)
        val corrections  = if (quality < MIN_ACCEPTABLE_QUALITY || !aligned) {
            buildQuickCorrections(quality, aligned, mistakeTypes, response)
        } else emptyList()

        val revisedText = if (corrections.isNotEmpty() && quality < LLM_REFLECTION_THRESHOLD) {
            generateImprovedResponse(response, query, corrections)
        } else null

        if (corrections.isNotEmpty()) recordMistakes(mistakeTypes, query)

        return ReflectionResult(
            planId          = null,
            qualityScore    = quality,
            completionRate  = 1f,
            isAcceptable    = quality >= MIN_ACCEPTABLE_QUALITY && aligned,
            goalAligned     = aligned,
            failedTaskCount = 0,
            corrections     = corrections,
            revisedResponse = revisedText,
            mistakeTypes    = mistakeTypes,
            improvementNote = if (corrections.isNotEmpty()) summariseCorrections(corrections) else null
        )
    }

    /**
     * Detect factual inconsistencies between [response] and [memoryContext].
     */
    fun detectInconsistencies(response: String, memoryContext: String): List<String> {
        val issues = mutableListOf<String>()
        val responseWords = response.lowercase().split("\\s+".toRegex()).toSet()
        val contextWords  = memoryContext.lowercase().split("\\s+".toRegex()).toSet()

        val negationPairs = listOf(
            Pair("not",   "is"),
            Pair("never", "always"),
            Pair("no",    "yes"),
            Pair("false", "true")
        )
        for ((neg, pos) in negationPairs) {
            if (neg in responseWords && pos in contextWords) {
                issues.add("Potential contradiction: response uses '$neg' but memory context has '$pos'")
            }
        }
        return issues
    }

    /** Returns the recent mistake log for diagnostic purposes. */
    fun getRecentMistakes(): List<MistakeEntry> = mistakeLog.toList()

    /** Check if a specific mistake pattern has been repeated recently. */
    fun hasMistakePattern(type: MistakeType): Boolean =
        mistakeLog.count { it.type == type } >= 2

    // ─── Phase 4 #4 — LLM-Based Response Improvement ─────────────────────────

    /**
     * Calls the LLM to generate a genuinely improved response.
     * Passes the original query, the weak response, and specific corrections.
     */
    private suspend fun generateImprovedResponse(
        original: String,
        query: String,
        corrections: List<String>
    ): String {
        if (corrections.isEmpty()) return original

        val correctionList = corrections.take(5).joinToString("\n") { "- $it" }
        val prompt = """
            The following response to a user query was evaluated and found to have issues.
            
            Original user query: "$query"
            
            Weak/incorrect response:
            "$original"
            
            Issues found:
            $correctionList
            
            Please rewrite the response to fix all the listed issues. 
            The new response should be:
            - Directly relevant to the original query
            - Complete and accurate
            - Natural and conversational
            - Free of the listed issues
            
            Improved response:
        """.trimIndent()

        return try {
            val improved = llmClient.complete(
                prompt,
                "You are an expert at improving AI assistant responses. Be concise and helpful."
            )
            Log.d(TAG, "LLM-improved response generated (${improved.length} chars)")
            improved.trim().ifBlank { original }
        } catch (e: Exception) {
            Log.w(TAG, "LLM improvement failed: ${e.message}")
            appendCorrectionsToResponse(original, corrections)
        }
    }

    // ─── Phase 4 #4 — Mistake Detection ──────────────────────────────────────

    private fun detectMistakeTypes(
        response: String,
        query: String,
        context: String = ""
    ): List<MistakeType> {
        val mistakes = mutableListOf<MistakeType>()
        val lower    = response.lowercase()
        val words    = response.split("\\s+".toRegex())

        if (response.isBlank() || words.size < 3) {
            mistakes.add(MistakeType.RESPONSE_TOO_SHORT)
        }

        if (lower.contains("[stub]") || lower.contains("[llm stub]") ||
            lower.contains("placeholder") || lower.contains("todo")) {
            mistakes.add(MistakeType.CONTAINS_STUB_CONTENT)
        }

        if ((lower.contains("i don't know") || lower.contains("i cannot") ||
             lower.contains("i'm not sure") || lower.contains("i am unable")) &&
            context.isNotBlank()) {
            mistakes.add(MistakeType.IGNORES_AVAILABLE_CONTEXT)
        }

        val queryWords  = query.lowercase().split("\\s+".toRegex()).filter { it.length > 4 }
        val responseText = lower
        val overlap     = queryWords.count { responseText.contains(it) }
        if (queryWords.size >= 3 && overlap.toFloat() / queryWords.size < 0.2f) {
            mistakes.add(MistakeType.OFF_TOPIC)
        }

        if (lower.contains("i don't know") && lower.contains("i cannot") &&
            lower.contains("i'm not sure")) {
            mistakes.add(MistakeType.EXCESSIVE_HEDGING)
        }

        if (words.size > 200) {
            mistakes.add(MistakeType.RESPONSE_TOO_LONG)
        }

        val repetitionCheck = words.windowed(5).count { window ->
            window.distinct().size < 3
        }
        if (repetitionCheck > 10) {
            mistakes.add(MistakeType.REPETITIVE_CONTENT)
        }

        return mistakes
    }

    // ─── Quality Evaluation ───────────────────────────────────────────────────

    private fun evaluateResponseQuality(
        response: String,
        query: String,
        mistakeTypes: List<MistakeType> = emptyList()
    ): Float {
        if (response.isBlank()) return 0f

        var score = 0.55f

        val wordCount = response.split("\\s+".toRegex()).size
        score += when {
            wordCount < 3   -> -0.35f
            wordCount < 8   ->  0f
            wordCount < 30  ->  0.10f
            wordCount < 100 ->  0.15f
            wordCount < 250 ->  0.10f
            else            ->  0.05f
        }

        val queryKeywords = query.lowercase().split("\\s+".toRegex())
            .filter { it.length > 3 }.toSet()
        val responseText  = response.lowercase()
        val keywordHits   = queryKeywords.count { responseText.contains(it) }
        val relevance     = if (queryKeywords.isEmpty()) 0.5f
            else (keywordHits.toFloat() / queryKeywords.size)
        score += relevance * 0.25f

        for (mistake in mistakeTypes) {
            score -= when (mistake) {
                MistakeType.RESPONSE_TOO_SHORT      -> 0.30f
                MistakeType.CONTAINS_STUB_CONTENT   -> 0.25f
                MistakeType.OFF_TOPIC               -> 0.20f
                MistakeType.IGNORES_AVAILABLE_CONTEXT -> 0.10f
                MistakeType.EXCESSIVE_HEDGING       -> 0.08f
                MistakeType.RESPONSE_TOO_LONG       -> 0.05f
                MistakeType.REPETITIVE_CONTENT      -> 0.07f
            }
        }

        return score.coerceIn(0f, 1f)
    }

    private fun checkGoalAlignment(response: String, query: String): Boolean {
        val queryWords = query.lowercase().split("\\s+".toRegex()).filter { it.length > 4 }.toSet()
        val responseText = response.lowercase()
        val overlap = queryWords.count { responseText.contains(it) }
        return queryWords.isEmpty() || (overlap.toFloat() / queryWords.size >= 0.25f)
    }

    // ─── Corrections Builder ──────────────────────────────────────────────────

    private fun buildCorrections(
        failedTasks: List<Task>,
        quality: Float,
        goalAligned: Boolean,
        response: String,
        query: String,
        mistakeTypes: List<MistakeType>
    ): List<String> {
        val corrections = mutableListOf<String>()

        if (failedTasks.isNotEmpty()) {
            corrections.add("${failedTasks.size} task(s) failed: ${failedTasks.map { it.name }.take(3)}")
        }

        for (mistake in mistakeTypes) {
            corrections.add(mistake.description)
        }

        if (!goalAligned) corrections.add("Response does not address the original query: \"${query.take(60)}\"")
        if (quality < 0.4f) corrections.add("Overall quality score is very low (${"%.2f".format(quality)})")

        return corrections.distinct()
    }

    private fun buildQuickCorrections(
        quality: Float,
        aligned: Boolean,
        mistakeTypes: List<MistakeType>,
        response: String
    ): List<String> {
        val corrections = mutableListOf<String>()
        if (quality < MIN_ACCEPTABLE_QUALITY) corrections.add("Quality below acceptable threshold (${"%.2f".format(quality)})")
        if (!aligned) corrections.add("Response is not aligned with the user's query")
        mistakeTypes.forEach { corrections.add(it.description) }
        return corrections
    }

    private fun summariseCorrections(corrections: List<String>): String {
        return "Found ${corrections.size} issue(s): ${corrections.take(3).joinToString("; ")}"
    }

    private fun appendCorrectionsToResponse(original: String, corrections: List<String>): String {
        val note = corrections.take(3).joinToString("; ")
        return "$original\n\n[Note: $note — this response may need improvement]"
    }

    // ─── Mistake Logging ──────────────────────────────────────────────────────

    private fun recordMistakes(types: List<MistakeType>, queryContext: String) {
        val now = System.currentTimeMillis()
        types.forEach { type ->
            if (mistakeLog.size >= MAX_MISTAKE_LOG) mistakeLog.removeFirst()
            mistakeLog.addLast(MistakeEntry(type = type, queryContext = queryContext.take(60), timestampMs = now))
        }
    }
}

// ─── Phase 4 Data Classes ──────────────────────────────────────────────────────

enum class MistakeType(val description: String) {
    RESPONSE_TOO_SHORT("Response is too short — expand the answer with more detail"),
    CONTAINS_STUB_CONTENT("Response contains placeholder/stub content — replace with real content"),
    OFF_TOPIC("Response is off-topic and does not address the user's question"),
    IGNORES_AVAILABLE_CONTEXT("Response ignores available memory/context that would improve accuracy"),
    EXCESSIVE_HEDGING("Response is overly hedged — provide a more direct, confident answer"),
    RESPONSE_TOO_LONG("Response is excessively long — be more concise"),
    REPETITIVE_CONTENT("Response contains repetitive content — remove redundancy")
}

data class MistakeEntry(
    val type: MistakeType,
    val queryContext: String,
    val timestampMs: Long
)

data class ReflectionResult(
    val planId: String?,
    val qualityScore: Float,
    val completionRate: Float,
    val isAcceptable: Boolean,
    val goalAligned: Boolean,
    val failedTaskCount: Int,
    val corrections: List<String>,
    val revisedResponse: String?,
    val mistakeTypes: List<MistakeType> = emptyList(),
    val improvementNote: String? = null
)
