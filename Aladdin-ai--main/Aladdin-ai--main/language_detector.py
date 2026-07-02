"""Automatic Language Detection — Feature 3, 4, 9, 10.

Detects Hindi, Gujarati, and English from transcribed text or raw audio.
Strategy (offline-first):
  1. Script/Unicode-block analysis  (<1 ms, zero dependencies) — primary.
  2. Keyword vocabulary scoring — secondary (handles Hinglish/Gujlish).
  3. Character n-gram confidence boosting — tertiary.
  4. langdetect library (optional soft-dep) — fallback if available.

Detection latency target: <200 ms (typically <1 ms via Unicode analysis).
"""

from __future__ import annotations

import logging
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

log = logging.getLogger(__name__)

# ── Supported language codes ──────────────────────────────────────────────────

LANG_HINDI = "hi"
LANG_GUJARATI = "gu"
LANG_ENGLISH = "en"
SUPPORTED_LANGS = [LANG_HINDI, LANG_GUJARATI, LANG_ENGLISH]

# ── Unicode block ranges ───────────────────────────────────────────────────────
# Devanagari (Hindi): U+0900 – U+097F
# Gujarati:           U+0A80 – U+0AFF
_DEVANAGARI_RE = re.compile(r"[\u0900-\u097F]")
_GUJARATI_RE = re.compile(r"[\u0A80-\u0AFF]")
_LATIN_RE = re.compile(r"[a-zA-Z]")

# ── Language-specific vocabulary (common words) ───────────────────────────────
_HINDI_LATIN_WORDS: set[str] = {
    "kya",
    "hai",
    "mera",
    "tera",
    "aaj",
    "kal",
    "abhi",
    "yahan",
    "wahan",
    "kaise",
    "kaisa",
    "theek",
    "bahut",
    "nahi",
    "haan",
    "main",
    "hum",
    "tum",
    "aap",
    "unka",
    "uska",
    "mujhe",
    "tumhe",
    "apna",
    "bolo",
    "karo",
    "dena",
    "lena",
    "jana",
    "aana",
    "suno",
    "dekho",
    "kitna",
    "kitne",
    "kitni",
    "mausam",
    "khana",
    "pani",
    "ghar",
    "kaam",
    "samay",
    "paisa",
    "achha",
    "shukriya",
    "namaste",
    "alvida",
    "boliye",
    "batao",
    # Hinglish markers
    "yaar",
    "bhai",
    "arre",
    "bilkul",
    "lekin",
    "kyunki",
    "isliye",
    "isliye",
}

_GUJARATI_LATIN_WORDS: set[str] = {
    "che",
    "chhe",
    "havu",
    "kem",
    "su",
    "tamaro",
    "maro",
    "aavu",
    "jaav",
    "khabar",
    "tame",
    "ane",
    "pan",
    "bahu",
    "nathi",
    "chho",
    "chu",
    "tamne",
    "mane",
    "ketalu",
    "kevi",
    "kidhi",
    "aapjo",
    "karjo",
    "joie",
    "chiye",
    "hava",
    "kaisu",
    "kaisun",
    "kaiso",
    "aaj",
    "aajkal",
    "saro",
    "saaru",
    "nathi",
    "nai",
    "hoy",
    "hoye",
    "pani",
    "khai",
    # Gujlish markers
    "bhai",
    "bhen",
    "badhu",
    "tamara",
    "amara",
    "avjo",
    "jao",
    "aavo",
}

_ENGLISH_WORDS: set[str] = {
    "what",
    "where",
    "when",
    "how",
    "why",
    "who",
    "which",
    "is",
    "are",
    "was",
    "were",
    "the",
    "a",
    "an",
    "and",
    "or",
    "but",
    "if",
    "then",
    "this",
    "that",
    "these",
    "those",
    "my",
    "your",
    "his",
    "her",
    "our",
    "their",
    "can",
    "will",
    "would",
    "should",
    "could",
    "may",
    "might",
    "do",
    "does",
    "did",
    "have",
    "has",
    "had",
    "get",
    "got",
    "set",
    "today",
    "weather",
    "time",
    "help",
    "please",
    "thank",
    "thanks",
    "yes",
    "no",
    "okay",
    "ok",
    "hi",
    "hello",
    "hey",
}


@dataclass
class DetectionResult:
    """Result of language detection."""

    language: str  # ISO 639-1 code: "hi", "gu", "en"
    confidence: float  # 0.0 – 1.0
    scores: Dict[str, float] = field(default_factory=dict)
    method: str = "unicode"  # detection method used
    is_mixed: bool = False  # Hinglish / Gujlish detected
    fallback_used: bool = False  # whether fallback chain was invoked

    @property
    def language_name(self) -> str:
        return {"hi": "Hindi", "gu": "Gujarati", "en": "English"}.get(
            self.language, self.language
        )

    def __str__(self) -> str:
        mixed = " [mixed]" if self.is_mixed else ""
        fb = " [fallback]" if self.fallback_used else ""
        return (
            f"{self.language_name}{mixed}{fb} ({self.confidence:.0%} via {self.method})"
        )


class LanguageDetector:
    """Offline-first multilingual language detector.

    Usage::

        detector = LanguageDetector()
        result = detector.detect("Aaj ka mausam kya hai?")
        log.info(result)  # "Hindi (82% via vocab)"
    """

    # Minimum confidence below which fallback is triggered
    CONFIDENCE_THRESHOLD = 0.55

    def __init__(self, default_language: str = LANG_ENGLISH):
        self._default = default_language
        self._langdetect_available = self._check_langdetect()
        log.info(
            "LanguageDetector ready (langdetect=%s, default=%s)",
            self._langdetect_available,
            default_language,
        )

    # ── Public API ────────────────────────────────────────────────────────────

    def detect(self, text: str) -> DetectionResult:
        """Detect language of *text*.  Returns a :class:`DetectionResult`."""
        if not text or not text.strip():
            return DetectionResult(self._default, 0.0, method="default")

        text = text.strip()

        # Step 1: Unicode block analysis (fastest, most reliable for native script)
        unicode_result = self._detect_by_unicode(text)
        if unicode_result.confidence >= self.CONFIDENCE_THRESHOLD:
            return unicode_result

        # Step 2: Vocabulary scoring (works for romanised/code-mixed)
        vocab_result = self._detect_by_vocab(text)
        if vocab_result.confidence >= self.CONFIDENCE_THRESHOLD:
            return vocab_result

        # Step 3: Character n-gram boosting
        ngram_result = self._detect_by_ngrams(text)
        if ngram_result.confidence >= self.CONFIDENCE_THRESHOLD:
            return ngram_result

        # Step 4: langdetect fallback (optional dependency)
        if self._langdetect_available:
            ld_result = self._detect_by_langdetect(text)
            if ld_result.confidence >= self.CONFIDENCE_THRESHOLD:
                ld_result.fallback_used = True
                return ld_result

        # Step 5: Best-of-all combined score
        combined = self._combine_results([unicode_result, vocab_result, ngram_result])
        if combined.confidence > 0:
            combined.fallback_used = combined.confidence < self.CONFIDENCE_THRESHOLD
            return combined

        return DetectionResult(self._default, 0.3, method="default", fallback_used=True)

    def detect_batch(self, texts: List[str]) -> List[DetectionResult]:
        """Detect language for a list of texts."""
        return [self.detect(t) for t in texts]

    def detect_with_history(
        self, text: str, history: List[DetectionResult], weight: float = 0.2
    ) -> DetectionResult:
        """Detect language, biased by conversation history for continuity.

        *history* is a recent window of prior detections.
        *weight* controls how much the prior context influences the result.
        """
        current = self.detect(text)
        if not history:
            return current

        # Accumulate prior language frequencies
        prior_counts: Dict[str, float] = {}
        for h in history[-5:]:  # last 5 turns
            prior_counts[h.language] = prior_counts.get(h.language, 0) + h.confidence

        # Normalise
        total = sum(prior_counts.values()) or 1.0
        prior: Dict[str, float] = {k: v / total for k, v in prior_counts.items()}

        # Blend
        scores = dict(current.scores)
        for lang, p in prior.items():
            scores[lang] = scores.get(lang, 0) * (1 - weight) + p * weight

        best_lang = max(scores, key=lambda k: scores[k])
        return DetectionResult(
            language=best_lang,
            confidence=min(scores[best_lang] + 0.1, 1.0),
            scores=scores,
            method=f"{current.method}+history",
            is_mixed=current.is_mixed,
        )

    # ── Detection methods ─────────────────────────────────────────────────────

    def _detect_by_unicode(self, text: str) -> DetectionResult:
        total_chars = max(len([c for c in text if not c.isspace()]), 1)
        hi_chars = len(_DEVANAGARI_RE.findall(text))
        gu_chars = len(_GUJARATI_RE.findall(text))
        en_chars = len(_LATIN_RE.findall(text))

        scores = {
            LANG_HINDI: hi_chars / total_chars,
            LANG_GUJARATI: gu_chars / total_chars,
            LANG_ENGLISH: en_chars / total_chars,
        }

        # If mixed Devanagari + Latin → Hinglish
        is_mixed = (hi_chars > 0 and en_chars > 0) or (gu_chars > 0 and en_chars > 0)

        best = max(scores, key=lambda k: scores[k])
        conf = scores[best]
        if conf < 0.1:
            return DetectionResult(self._default, 0.0, scores=scores, method="unicode")

        return DetectionResult(
            language=best,
            confidence=min(conf * 1.2, 1.0),  # slight boost for native script
            scores=scores,
            method="unicode",
            is_mixed=is_mixed,
        )

    def _detect_by_vocab(self, text: str) -> DetectionResult:
        words = re.findall(r"\b[a-zA-Z]+\b", text.lower())
        if not words:
            return DetectionResult(self._default, 0.0, method="vocab")

        hi_hits = sum(1 for w in words if w in _HINDI_LATIN_WORDS)
        gu_hits = sum(1 for w in words if w in _GUJARATI_LATIN_WORDS)
        en_hits = sum(1 for w in words if w in _ENGLISH_WORDS)

        total_hits = hi_hits + gu_hits + en_hits
        if total_hits == 0:
            return DetectionResult(self._default, 0.0, method="vocab")

        scores = {
            LANG_HINDI: hi_hits / total_hits,
            LANG_GUJARATI: gu_hits / total_hits,
            LANG_ENGLISH: en_hits / total_hits,
        }

        best = max(scores, key=lambda k: scores[k])
        # Penalise if it's too close (ambiguous)
        sorted_scores = sorted(scores.values(), reverse=True)
        confidence = sorted_scores[0]
        if len(sorted_scores) > 1 and sorted_scores[0] - sorted_scores[1] < 0.2:
            confidence *= 0.8  # penalise ambiguity

        is_mixed = (hi_hits > 0 and en_hits > 0) or (gu_hits > 0 and en_hits > 0)

        return DetectionResult(
            language=best,
            confidence=confidence,
            scores=scores,
            method="vocab",
            is_mixed=is_mixed,
        )

    def _detect_by_ngrams(self, text: str) -> DetectionResult:
        """Character bigram analysis for romanised Indian languages."""
        # Common character bigrams distinctive per language
        _HI_BIGRAMS = {
            "aa",
            "ee",
            "ai",
            "au",
            "ka",
            "ki",
            "ke",
            "ko",
            "na",
            "ni",
            "ha",
            "hi",
            "hu",
            "ne",
            "nu",
            "ta",
            "ti",
            "tu",
            "bh",
            "kh",
            "gh",
            "ch",
            "jh",
            "th",
            "dh",
            "ph",
            "sh",
            "ya",
            "ra",
            "la",
        }
        _GU_BIGRAMS = {
            "ch",
            "he",
            "se",
            "ne",
            "ma",
            "va",
            "aa",
            "au",
            "ae",
            "oi",
            "ko",
            "jo",
            "no",
            "mo",
            "vo",
            "lo",
            "po",
            "bo",
            "go",
            "ro",
        }
        _EN_BIGRAMS = {
            "th",
            "he",
            "in",
            "er",
            "an",
            "re",
            "on",
            "en",
            "at",
            "es",
            "st",
            "nt",
            "is",
            "it",
            "to",
            "ng",
            "or",
            "al",
            "ou",
            "ar",
        }

        lowered = text.lower()
        bigrams = [
            lowered[i : i + 2]
            for i in range(len(lowered) - 1)
            if lowered[i].isalpha() and lowered[i + 1].isalpha()
        ]
        if not bigrams:
            return DetectionResult(self._default, 0.0, method="ngram")

        hi_score = sum(1 for b in bigrams if b in _HI_BIGRAMS) / len(bigrams)
        gu_score = sum(1 for b in bigrams if b in _GU_BIGRAMS) / len(bigrams)
        en_score = sum(1 for b in bigrams if b in _EN_BIGRAMS) / len(bigrams)

        scores = {LANG_HINDI: hi_score, LANG_GUJARATI: gu_score, LANG_ENGLISH: en_score}
        best = max(scores, key=lambda k: scores[k])
        return DetectionResult(
            language=best,
            confidence=scores[best] * 0.8,  # ngram alone is less reliable
            scores=scores,
            method="ngram",
        )

    def _detect_by_langdetect(self, text: str) -> DetectionResult:
        try:
            from langdetect import detect_langs  # type: ignore

            results = detect_langs(text)
            for r in results:
                lang = r.lang
                # langdetect uses "hi" for Hindi; Gujarati may map to "gu"
                if lang in SUPPORTED_LANGS:
                    return DetectionResult(
                        language=lang, confidence=r.prob, method="langdetect"
                    )
                # "mr" (Marathi) close to Hindi
                if lang == "mr":
                    return DetectionResult(
                        language=LANG_HINDI,
                        confidence=r.prob * 0.8,
                        method="langdetect",
                    )
        except Exception as e:
            log.debug("langdetect error: %s", e)
        return DetectionResult(self._default, 0.0, method="langdetect")

    def _combine_results(self, results: List[DetectionResult]) -> DetectionResult:
        combined_scores: Dict[str, float] = {}
        for r in results:
            for lang, score in r.scores.items():
                combined_scores[lang] = combined_scores.get(lang, 0) + score
        if not combined_scores:
            return DetectionResult(self._default, 0.0, method="combined")
        total = sum(combined_scores.values()) or 1.0
        norm = {k: v / total for k, v in combined_scores.items()}
        best = max(norm, key=lambda k: norm[k])
        is_mixed = any(r.is_mixed for r in results)
        return DetectionResult(
            language=best,
            confidence=norm[best],
            scores=norm,
            method="combined",
            is_mixed=is_mixed,
        )

    # ── Helpers ───────────────────────────────────────────────────────────────

    @staticmethod
    def _check_langdetect() -> bool:
        try:
            import langdetect  # type: ignore  # noqa: F401

            return True
        except ImportError:
            return False

    @property
    def default_language(self) -> str:
        return self._default

    @default_language.setter
    def default_language(self, value: str) -> None:
        if value not in SUPPORTED_LANGS:
            raise ValueError(
                f"Unsupported language: {value}. Choose from {SUPPORTED_LANGS}"
            )
        self._default = value


# ── Convenience singleton ─────────────────────────────────────────────────────

_detector: Optional[LanguageDetector] = None


def get_detector(default_language: str = LANG_ENGLISH) -> LanguageDetector:
    """Return or create the shared LanguageDetector singleton."""
    global _detector
    if _detector is None:
        _detector = LanguageDetector(default_language)
    return _detector


def detect_language(text: str, default: str = LANG_ENGLISH) -> DetectionResult:
    """Quick one-shot language detection. Uses the shared singleton."""
    return get_detector(default).detect(text)
