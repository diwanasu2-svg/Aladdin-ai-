package com.aladdin.engine.intent

import android.util.Log
import com.aladdin.engine.models.ClassifiedIntent
import com.aladdin.engine.models.IntentType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies user utterances into one of 18 intent types.
 *
 * Two-stage pipeline:
 *   1. Rule-based classifier (instant, no LLM) — handles high-confidence cases
 *   2. LLM-based classifier (fallback) — for ambiguous inputs
 *
 * Also extracts named entities (slot filling) for intent parameters.
 *
 * Supports:
 *   QUESTION_ANSWERING, FACTUAL_LOOKUP, WEATHER_QUERY, NEWS_QUERY,
 *   SET_REMINDER, SEND_MESSAGE, PLAY_MUSIC, SEARCH_WEB, OPEN_APP,
 *   NAVIGATE, REMEMBER_FACT, RECALL_MEMORY, CREATE_PLAN, TRACK_GOAL,
 *   UPDATE_PROJECT, SMALL_TALK, CLARIFICATION_REQUEST, UNKNOWN
 */
@Singleton
class IntentClassifier @Inject constructor() {

    companion object {
        private const val TAG = "IntentClassifier"
        private const val HIGH_CONFIDENCE = 0.92f
        private const val MEDIUM_CONFIDENCE = 0.75f
        private const val LOW_CONFIDENCE = 0.55f
    }

    // ─── Rule definitions ─────────────────────────────────────────────────────

    private data class IntentRule(
        val intent: IntentType,
        val keywords: List<String>,
        val patterns: List<Regex>,
        val confidence: Float
    )

    private val rules: List<IntentRule> = listOf(
        IntentRule(
            IntentType.WEATHER_QUERY,
            listOf("weather", "temperature", "forecast", "rain", "sunny", "cloudy", "humidity", "wind"),
            listOf(Regex("(what'?s? the |how'?s? the |will it )?(weather|temperature|forecast)", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.SET_REMINDER,
            listOf("remind", "reminder", "alert", "notify", "don't let me forget", "set an alarm"),
            listOf(Regex("remind (me|us) (to|about|at|in)", RegexOption.IGNORE_CASE),
                   Regex("set (a )?reminder", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.SEND_MESSAGE,
            listOf("send", "message", "text", "email", "whatsapp", "tell", "notify"),
            listOf(Regex("send (a )?(message|text|email|whatsapp) to", RegexOption.IGNORE_CASE),
                   Regex("(text|message|email) \\w+", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.PLAY_MUSIC,
            listOf("play", "music", "song", "artist", "album", "playlist", "shuffle"),
            listOf(Regex("play (some |a )?\\w+", RegexOption.IGNORE_CASE),
                   Regex("put on (some )?\\w+", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.NAVIGATE,
            listOf("navigate", "directions", "route", "take me", "how do i get", "way to", "drive to", "walk to"),
            listOf(Regex("(navigate|directions|route) to", RegexOption.IGNORE_CASE),
                   Regex("(take me|get me) to", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.SEARCH_WEB,
            listOf("search", "google", "look up", "find", "browse", "show me"),
            listOf(Regex("(search|google|look up|find|browse) (for |on )?(the )?\\w+", RegexOption.IGNORE_CASE)),
            MEDIUM_CONFIDENCE
        ),
        IntentRule(
            IntentType.OPEN_APP,
            listOf("open", "launch", "start", "run"),
            listOf(Regex("(open|launch|start|run) (the )?\\w+( app)?", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.REMEMBER_FACT,
            listOf("remember", "store", "save", "note that", "don't forget", "keep in mind", "memorize"),
            listOf(Regex("(remember|note|store|save|memorize) (that |this )?", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.RECALL_MEMORY,
            listOf("recall", "what did i", "do you remember", "what was", "remind me of"),
            listOf(Regex("(do you |can you )?remember (when|what|where|who|how)", RegexOption.IGNORE_CASE),
                   Regex("what did (i|we) (say|talk|discuss)", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.CREATE_PLAN,
            listOf("plan", "schedule", "organize", "steps to", "how to", "strategy", "roadmap"),
            listOf(Regex("(create|make|build|develop) (a )?(plan|schedule|strategy|roadmap)", RegexOption.IGNORE_CASE),
                   Regex("(what are |give me )(the )?(steps|phases|stages) (to|for)", RegexOption.IGNORE_CASE)),
            MEDIUM_CONFIDENCE
        ),
        IntentRule(
            IntentType.TRACK_GOAL,
            listOf("goal", "objective", "target", "milestone", "progress", "track", "achieve"),
            listOf(Regex("(set|track|add|update) (a |my )?(goal|objective|target)", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.UPDATE_PROJECT,
            listOf("project", "task", "sprint", "update", "progress", "status"),
            listOf(Regex("(update|change|modify) (the )?(project|task|status)", RegexOption.IGNORE_CASE)),
            MEDIUM_CONFIDENCE
        ),
        IntentRule(
            IntentType.NEWS_QUERY,
            listOf("news", "headlines", "latest", "current events", "what's happening"),
            listOf(Regex("(what'?s? (in |the )?(news|headlines)|latest news|current events)", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.FACTUAL_LOOKUP,
            listOf("what is", "who is", "when was", "where is", "how many", "define", "tell me about"),
            listOf(Regex("^(what|who|when|where|how|why|which) (is|are|was|were|did|does|do)", RegexOption.IGNORE_CASE)),
            MEDIUM_CONFIDENCE
        ),
        IntentRule(
            IntentType.SMALL_TALK,
            listOf("hello", "hi", "hey", "how are you", "good morning", "good night", "thanks", "bye", "goodbye", "nice"),
            listOf(Regex("^(hi|hey|hello|howdy|sup|what'?s? up)", RegexOption.IGNORE_CASE),
                   Regex("(how are you|how'?s? it going|you doing)", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        ),
        IntentRule(
            IntentType.CLARIFICATION_REQUEST,
            listOf("what do you mean", "clarify", "explain", "elaborate", "i don't understand", "confused"),
            listOf(Regex("(what do you mean|can you (clarify|explain|elaborate))", RegexOption.IGNORE_CASE)),
            HIGH_CONFIDENCE
        )
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Classify a user utterance.
     * Returns a [ClassifiedIntent] with the best-matching intent type,
     * confidence score, and extracted entities.
     */
    fun classify(query: String): ClassifiedIntent {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) {
            return ClassifiedIntent(IntentType.UNKNOWN, 0f, rawQuery = query)
        }

        // Stage 1: rule-based
        val ruleMatch = classifyByRules(normalized, query)
        if (ruleMatch != null && ruleMatch.confidence >= MEDIUM_CONFIDENCE) {
            Log.d(TAG, "Rule classified: ${ruleMatch.type} (${ruleMatch.confidence})")
            return ruleMatch
        }

        // Stage 2: question detection heuristic
        val questionMatch = detectQuestion(normalized, query)
        if (questionMatch != null) return questionMatch

        // Stage 3: fallback — UNKNOWN with low confidence
        Log.d(TAG, "Intent unresolved for: '${query.take(50)}'")
        return ruleMatch ?: ClassifiedIntent(IntentType.UNKNOWN, LOW_CONFIDENCE, rawQuery = query)
    }

    /**
     * Classify with an LLM prompt as override (call from LLM response parser).
     * Expected JSON: {"intent":"WEATHER_QUERY","confidence":0.95,"entities":{"location":"London"}}
     */
    fun classifyFromLLMJson(json: String, rawQuery: String): ClassifiedIntent {
        return try {
            val intentStr = extractJsonString(json, "intent") ?: "UNKNOWN"
            val confidence = extractJsonFloat(json, "confidence") ?: 0.7f
            val entitiesJson = extractJsonObject(json, "entities") ?: "{}"
            val entities = parseEntities(entitiesJson)
            val intentType = try { IntentType.valueOf(intentStr) } catch (_: Exception) { IntentType.UNKNOWN }
            ClassifiedIntent(intentType, confidence, entities, rawQuery)
        } catch (e: Exception) {
            Log.w(TAG, "LLM JSON parse failed: ${e.message}")
            ClassifiedIntent(IntentType.UNKNOWN, 0.5f, rawQuery = rawQuery)
        }
    }

    // ─── Entity Extraction ────────────────────────────────────────────────────

    /** Extract named entities from a query given a known intent type. */
    fun extractEntities(query: String, intent: IntentType): Map<String, String> {
        val entities = mutableMapOf<String, String>()
        when (intent) {
            IntentType.SET_REMINDER -> {
                extractTime(query)?.let { entities["time"] = it }
                extractDuration(query)?.let { entities["duration"] = it }
                extractSubjectAfter(query, listOf("remind me to", "reminder to", "about"))?.let { entities["subject"] = it }
            }
            IntentType.SEND_MESSAGE -> {
                extractPersonName(query)?.let { entities["recipient"] = it }
                extractSubjectAfter(query, listOf("saying", "that", ":"))?.let { entities["message"] = it }
            }
            IntentType.NAVIGATE -> {
                extractSubjectAfter(query, listOf("to", "navigate to", "directions to", "take me to"))?.let { entities["destination"] = it }
            }
            IntentType.WEATHER_QUERY -> {
                extractLocation(query)?.let { entities["location"] = it }
            }
            IntentType.PLAY_MUSIC -> {
                extractSubjectAfter(query, listOf("play", "put on", "start"))?.let { entities["query"] = it }
            }
            IntentType.OPEN_APP -> {
                extractSubjectAfter(query, listOf("open", "launch", "start", "run"))?.let { entities["app"] = it }
            }
            IntentType.SEARCH_WEB -> {
                extractSubjectAfter(query, listOf("search for", "search", "google", "look up", "find"))?.let { entities["query"] = it }
            }
            IntentType.REMEMBER_FACT -> {
                extractSubjectAfter(query, listOf("remember that", "note that", "remember", "memorize"))?.let { entities["fact"] = it }
            }
            else -> {}
        }
        return entities
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────

    private fun classifyByRules(normalized: String, raw: String): ClassifiedIntent? {
        data class Candidate(val intent: IntentType, val score: Float)
        val candidates = mutableListOf<Candidate>()

        for (rule in rules) {
            var score = 0f
            val keywordHits = rule.keywords.count { normalized.contains(it) }
            if (keywordHits > 0) score += (keywordHits.toFloat() / rule.keywords.size) * 0.5f
            if (rule.patterns.any { it.containsMatchIn(normalized) }) score += 0.5f
            if (score > 0) candidates.add(Candidate(rule.intent, (score * rule.confidence).coerceIn(0f, 1f)))
        }

        val best = candidates.maxByOrNull { it.score } ?: return null
        val entities = extractEntities(raw, best.intent)
        return ClassifiedIntent(best.intent, best.score, entities, raw)
    }

    private fun detectQuestion(normalized: String, raw: String): ClassifiedIntent? {
        val questionStarters = listOf("what", "who", "when", "where", "how", "why", "which", "is ", "are ", "was ", "do ", "does ", "can ", "will ")
        if (questionStarters.any { normalized.startsWith(it) } || normalized.endsWith("?")) {
            return ClassifiedIntent(IntentType.QUESTION_ANSWERING, MEDIUM_CONFIDENCE, rawQuery = raw)
        }
        return null
    }

    private fun extractTime(text: String): String? {
        val patterns = listOf(
            Regex("at (\\d{1,2}:\\d{2}( ?[ap]m)?)", RegexOption.IGNORE_CASE),
            Regex("at (\\d{1,2} ?[ap]m)", RegexOption.IGNORE_CASE),
            Regex("(tomorrow|today|tonight|this (morning|afternoon|evening))", RegexOption.IGNORE_CASE),
            Regex("(\\d{1,2}:\\d{2})", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) { val m = p.find(text); if (m != null) return m.groupValues.firstOrNull { it.isNotBlank() } }
        return null
    }

    private fun extractDuration(text: String): String? {
        val p = Regex("in (\\d+ ?(minutes?|hours?|days?|weeks?))", RegexOption.IGNORE_CASE)
        return p.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractLocation(text: String): String? {
        val p = Regex("in ([A-Z][a-z]+(?: [A-Z][a-z]+)*)")
        return p.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractPersonName(text: String): String? {
        val p = Regex("(?:to|for) ([A-Z][a-z]+(?:\\s[A-Z][a-z]+)?)")
        return p.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractSubjectAfter(text: String, triggers: List<String>): String? {
        val lower = text.lowercase()
        for (t in triggers) {
            val idx = lower.indexOf(t)
            if (idx >= 0) {
                val after = text.substring(idx + t.length).trim()
                if (after.isNotBlank()) return after.take(200)
            }
        }
        return null
    }

    private fun extractJsonString(json: String, key: String): String? {
        val p = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return p.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val p = Regex("\"$key\"\\s*:\\s*([0-9.]+)")
        return p.find(json)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val p = Regex("\"$key\"\\s*:\\s*(\\{[^}]*\\})")
        return p.find(json)?.groupValues?.getOrNull(1)
    }

    private fun parseEntities(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val p = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
        p.findAll(json).forEach { m -> result[m.groupValues[1]] = m.groupValues[2] }
        return result
    }
}
