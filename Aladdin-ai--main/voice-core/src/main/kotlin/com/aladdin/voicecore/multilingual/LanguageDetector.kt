package com.aladdin.voicecore.multilingual

import android.util.Log

/**
 * Offline language detector — Features 3, 4, 11.
 *
 * Detection pipeline (fastest → most accurate):
 *  1. Unicode block analysis  (<1 ms) — Devanagari vs Gujarati vs Latin
 *  2. Vocabulary keyword scoring  — handles Hinglish / Gujlish
 *  3. Character bigram analysis  — tiebreaker
 *  4. History-biased smoothing  — conversation continuity
 *
 * Latency target: <200 ms (typically <1 ms via Unicode step).
 */
class LanguageDetector(
    private val defaultLanguage: String = LANG_ENGLISH,
    private val confidenceThreshold: Float = 0.55f,
    private val historyWindow: Int = 5,
    private val historyWeight: Float = 0.2f,
) {
    companion object {
        private const val TAG = "LanguageDetector"

        // ── Unicode block ranges ──────────────────────────────────────────────
        // Devanagari (Hindi): U+0900 – U+097F
        // Gujarati:           U+0A80 – U+0AFF
        private fun isDevanagari(c: Char) = c.code in 0x0900..0x097F
        private fun isGujarati(c: Char)   = c.code in 0x0A80..0x0AFF
        private fun isLatin(c: Char)      = c in 'A'..'Z' || c in 'a'..'z'

        // ── Hindi vocabulary (romanised) ──────────────────────────────────────
        private val HINDI_WORDS = setOf(
            "kya", "hai", "mera", "tera", "aaj", "kal", "abhi", "yahan", "wahan",
            "kaise", "kaisa", "theek", "bahut", "nahi", "haan", "main", "hum", "tum",
            "aap", "mujhe", "tumhe", "apna", "bolo", "karo", "dena", "lena", "jana",
            "aana", "suno", "dekho", "kitna", "kitne", "kitni", "mausam", "khana",
            "pani", "ghar", "kaam", "samay", "paisa", "achha", "shukriya", "namaste",
            "alvida", "boliye", "batao", "yaar", "bhai", "arre", "bilkul", "lekin",
            "kyunki", "isliye", "nahin", "toh", "phir", "abhi", "waise", "sirf",
        )

        // ── Gujarati vocabulary (romanised) ───────────────────────────────────
        private val GUJARATI_WORDS = setOf(
            "che", "chhe", "havu", "kem", "su", "tamaro", "maro", "aavu", "jaav",
            "khabar", "tame", "ane", "pan", "bahu", "nathi", "chho", "chu", "tamne",
            "mane", "ketalu", "kevi", "kidhi", "aapjo", "karjo", "joie", "chiye",
            "hava", "kaisu", "kaisun", "kaiso", "aajkal", "saro", "saaru", "hoy",
            "hoye", "khai", "badhu", "tamara", "amara", "avjo", "jao", "aavo",
            "bhen", "game", "gamtu", "kevi", "ketla",
        )

        // ── English vocabulary ────────────────────────────────────────────────
        private val ENGLISH_WORDS = setOf(
            "what", "where", "when", "how", "why", "who", "which", "is", "are",
            "was", "were", "the", "and", "or", "but", "if", "then", "this", "that",
            "my", "your", "his", "her", "our", "their", "can", "will", "would",
            "should", "could", "may", "might", "do", "does", "did", "have", "has",
            "had", "get", "got", "today", "weather", "time", "help", "please",
            "thank", "thanks", "yes", "no", "okay", "ok", "hi", "hello", "hey",
        )

        // ── Bigrams distinctive for each language ─────────────────────────────
        private val HINDI_BIGRAMS   = setOf("aa", "ee", "bh", "kh", "gh", "ch", "jh",
            "th", "dh", "ph", "sh", "ya", "ra", "la", "ka", "ki", "na", "ni", "ha")
        private val GUJARATI_BIGRAMS = setOf("ch", "he", "se", "ne", "ma", "va", "au",
            "ae", "oi", "ko", "jo", "no", "mo", "vo", "lo", "po", "bo", "go", "ro")
        private val ENGLISH_BIGRAMS  = setOf("th", "he", "in", "er", "an", "re", "on",
            "en", "at", "es", "st", "nt", "is", "it", "to", "ng", "or", "al", "ou")
    }

    // Recent detection history for conversation continuity
    private val history = ArrayDeque<LanguageDetectionResult>(historyWindow)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Detect the language of [text].
     * Uses history for conversation continuity if available.
     */
    fun detect(text: String): LanguageDetectionResult {
        if (text.isBlank()) {
            return LanguageDetectionResult(defaultLanguage, 0f, "default")
        }

        val start = System.currentTimeMillis()

        // Step 1: Unicode block analysis
        val unicodeResult = detectByUnicode(text)
        if (unicodeResult.confidence >= confidenceThreshold) {
            val result = applyHistory(unicodeResult)
            updateHistory(result)
            Log.d(TAG, "Detected '${result.language}' (${result.confidence}) via ${result.method} in ${System.currentTimeMillis()-start}ms")
            return result
        }

        // Step 2: Vocabulary scoring
        val vocabResult = detectByVocab(text)
        if (vocabResult.confidence >= confidenceThreshold) {
            val result = applyHistory(vocabResult)
            updateHistory(result)
            Log.d(TAG, "Detected '${result.language}' (${result.confidence}) via ${result.method} in ${System.currentTimeMillis()-start}ms")
            return result
        }

        // Step 3: Bigram analysis
        val bigramResult = detectByBigrams(text)
        if (bigramResult.confidence >= confidenceThreshold) {
            val result = applyHistory(bigramResult)
            updateHistory(result)
            Log.d(TAG, "Detected '${result.language}' (${result.confidence}) via ${result.method} in ${System.currentTimeMillis()-start}ms")
            return result
        }

        // Step 4: Best of all combined, then apply history bias
        val combined = combineResults(listOf(unicodeResult, vocabResult, bigramResult))
        val result = applyHistory(combined)
        updateHistory(result)
        Log.d(TAG, "Detected '${result.language}' (${result.confidence}) via ${result.method} in ${System.currentTimeMillis()-start}ms")
        return result
    }

    /**
     * Clear conversation history (call at session start).
     */
    fun clearHistory() = history.clear()

    // ── Detection steps ────────────────────────────────────────────────────────

    private fun detectByUnicode(text: String): LanguageDetectionResult {
        var hiChars = 0; var guChars = 0; var laChars = 0; var total = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            total++
            when {
                isDevanagari(c) -> hiChars++
                isGujarati(c)   -> guChars++
                isLatin(c)      -> laChars++
            }
        }
        if (total == 0) return LanguageDetectionResult(defaultLanguage, 0f, "unicode")

        val hiScore = hiChars.toFloat() / total
        val guScore = guChars.toFloat() / total
        val enScore = laChars.toFloat() / total

        val (best, conf) = maxOf(
            LANG_HINDI    to hiScore,
            LANG_GUJARATI to guScore,
            LANG_ENGLISH  to enScore,
            comparator = compareBy { it.second }
        )
        val isMixed = (hiChars > 0 && laChars > 0) || (guChars > 0 && laChars > 0)
        return LanguageDetectionResult(
            language = best,
            confidence = (conf * 1.2f).coerceAtMost(1f),
            method = "unicode",
            isMixed = isMixed,
        )
    }

    private fun detectByVocab(text: String): LanguageDetectionResult {
        val words = text.lowercase().split(Regex("[^a-zA-Z]+")).filter { it.length > 1 }
        if (words.isEmpty()) return LanguageDetectionResult(defaultLanguage, 0f, "vocab")

        val hiHits = words.count { it in HINDI_WORDS }
        val guHits = words.count { it in GUJARATI_WORDS }
        val enHits = words.count { it in ENGLISH_WORDS }
        val totalHits = hiHits + guHits + enHits
        if (totalHits == 0) return LanguageDetectionResult(defaultLanguage, 0f, "vocab")

        val hiScore = hiHits.toFloat() / totalHits
        val guScore = guHits.toFloat() / totalHits
        val enScore = enHits.toFloat() / totalHits
        val (best, conf) = maxOf(
            LANG_HINDI    to hiScore,
            LANG_GUJARATI to guScore,
            LANG_ENGLISH  to enScore,
            comparator = compareBy { it.second }
        )
        val isMixed = (hiHits > 0 && enHits > 0) || (guHits > 0 && enHits > 0)
        // Penalise ambiguity
        val sortedScores = listOf(hiScore, guScore, enScore).sortedDescending()
        val penalty = if (sortedScores[0] - sortedScores[1] < 0.2f) 0.8f else 1f

        return LanguageDetectionResult(
            language = best,
            confidence = (conf * penalty).coerceAtMost(1f),
            method = "vocab",
            isMixed = isMixed,
        )
    }

    private fun detectByBigrams(text: String): LanguageDetectionResult {
        val lower = text.lowercase()
        val bigrams = mutableListOf<String>()
        for (i in 0 until lower.length - 1) {
            if (lower[i].isLetter() && lower[i + 1].isLetter()) {
                bigrams.add("${lower[i]}${lower[i + 1]}")
            }
        }
        if (bigrams.isEmpty()) return LanguageDetectionResult(defaultLanguage, 0f, "bigram")

        val hiScore = bigrams.count { it in HINDI_BIGRAMS }.toFloat() / bigrams.size
        val guScore = bigrams.count { it in GUJARATI_BIGRAMS }.toFloat() / bigrams.size
        val enScore = bigrams.count { it in ENGLISH_BIGRAMS }.toFloat() / bigrams.size

        val (best, conf) = maxOf(
            LANG_HINDI    to hiScore,
            LANG_GUJARATI to guScore,
            LANG_ENGLISH  to enScore,
            comparator = compareBy { it.second }
        )
        return LanguageDetectionResult(
            language = best,
            confidence = (conf * 0.8f).coerceAtMost(1f),
            method = "bigram",
        )
    }

    private fun combineResults(results: List<LanguageDetectionResult>): LanguageDetectionResult {
        val scores = mutableMapOf(LANG_HINDI to 0f, LANG_GUJARATI to 0f, LANG_ENGLISH to 0f)
        for (r in results) {
            // Accumulate confidence per language
            val step = when (r.method) {
                "unicode" -> 1.5f  // unicode is most reliable
                "vocab"   -> 1.2f
                else      -> 1.0f
            }
            scores[r.language] = (scores[r.language] ?: 0f) + r.confidence * step
        }
        val total = scores.values.sum().takeIf { it > 0f } ?: 1f
        val norm = scores.mapValues { it.value / total }
        val best = norm.maxByOrNull { it.value }!!
        val isMixed = results.any { it.isMixed }
        return LanguageDetectionResult(
            language = best.key,
            confidence = best.value,
            method = "combined",
            isMixed = isMixed,
        )
    }

    // ── History bias ───────────────────────────────────────────────────────────

    private fun applyHistory(current: LanguageDetectionResult): LanguageDetectionResult {
        if (history.isEmpty()) return current

        // Count prior language frequencies weighted by recency
        val priorCounts = mutableMapOf<String, Float>()
        history.forEachIndexed { idx, h ->
            val weight = (idx + 1).toFloat() / history.size  // more recent = higher weight
            priorCounts[h.language] = (priorCounts[h.language] ?: 0f) + h.confidence * weight
        }
        val priorTotal = priorCounts.values.sum().takeIf { it > 0f } ?: 1f
        val prior = priorCounts.mapValues { it.value / priorTotal }

        // Blend current scores with prior
        val scores = mutableMapOf(
            LANG_HINDI    to (if (current.language == LANG_HINDI) current.confidence else 0f),
            LANG_GUJARATI to (if (current.language == LANG_GUJARATI) current.confidence else 0f),
            LANG_ENGLISH  to (if (current.language == LANG_ENGLISH) current.confidence else 0f),
        )
        for ((lang, p) in prior) {
            scores[lang] = (scores[lang] ?: 0f) * (1f - historyWeight) + p * historyWeight
        }
        val best = scores.maxByOrNull { it.value }!!
        return current.copy(
            language = best.key,
            confidence = (best.value + 0.05f).coerceAtMost(1f),
            method = "${current.method}+history",
        )
    }

    private fun updateHistory(result: LanguageDetectionResult) {
        if (history.size >= historyWindow) history.removeFirst()
        history.addLast(result)
    }
}
