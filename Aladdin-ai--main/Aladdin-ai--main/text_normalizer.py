"""Language-Specific Text Normalization — Feature 12.

Handles:
- Hindi (Devanagari + romanised/Hinglish) punctuation and sentence boundaries
- Gujarati (Gujarati script + romanised/Gujlish) punctuation
- English standard normalization
- Number formatting per language
- Unicode cleanup and script validation
- TTS pre-processing (removes characters that confuse synthesizers)
"""

from __future__ import annotations

import re
import unicodedata
from typing import Optional

from language_detector import LANG_ENGLISH, LANG_GUJARATI, LANG_HINDI, DetectionResult


class TextNormalizer:
    """Normalize text for TTS and STT pipelines per language."""

    # ── Devanagari punctuation map ─────────────────────────────────────────────
    # Normalize Devanagari danda (।) to period and double-danda (॥) to double period
    _DANDA_RE = re.compile(r"[।॥]")

    # ── Gujarati-specific cleanup ─────────────────────────────────────────────
    _GU_DANDA_RE = re.compile(r"[।॥]")  # Gujarati also uses danda in some contexts

    # ── Common noise patterns ─────────────────────────────────────────────────
    _MULTI_SPACE_RE = re.compile(r"  +")
    _MULTI_NEWLINE_RE = re.compile(r"\n{3,}")
    _LEADING_PUNCT_RE = re.compile(r"^[,;:\-–—]+\s*")
    _BRACKET_CONTENT_RE = re.compile(r"\[.*?\]|\(.*?\)")  # remove bracketed annotations

    # ── Number patterns ───────────────────────────────────────────────────────
    # Matches bare digits for language-specific formatting
    _NUMBER_RE = re.compile(r"\b\d+\b")

    # Hindi number words (for TTS hints — not full conversion, just pronunciation hints)
    _HI_DIGITS = ["शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ"]

    def normalize(self, text: str, language: str, for_tts: bool = True) -> str:
        """Normalize *text* for *language*.

        Args:
            text:     Input text (may be in native script or romanised).
            language: ISO 639-1 code — "hi", "gu", or "en".
            for_tts:  If True, apply TTS-specific cleanup (strip markdown, etc.).

        Returns:
            Cleaned, normalized text suitable for TTS or display.
        """
        if not text:
            return text

        text = self._base_cleanup(text, for_tts=for_tts)

        if language == LANG_HINDI:
            text = self._normalize_hindi(text)
        elif language == LANG_GUJARATI:
            text = self._normalize_gujarati(text)
        else:
            text = self._normalize_english(text)

        text = self._final_cleanup(text)
        return text

    # ── Base cleanup (all languages) ──────────────────────────────────────────

    def _base_cleanup(self, text: str, for_tts: bool = True) -> str:
        # NFC normalization — canonical composition (important for Indic scripts)
        text = unicodedata.normalize("NFC", text)

        # Remove null bytes and control characters (except newline/tab)
        text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", text)

        if for_tts:
            # Strip markdown — bold, italic, code blocks confuse TTS
            text = re.sub(r"\*{1,3}(.*?)\*{1,3}", r"\1", text)
            text = re.sub(r"_{1,2}(.*?)_{1,2}", r"\1", text)
            text = re.sub(r"`{1,3}.*?`{1,3}", "", text, flags=re.DOTALL)
            text = re.sub(r"#{1,6}\s*", "", text)
            # Remove URLs (TTS reads them letter-by-letter which sounds terrible)
            text = re.sub(r"https?://\S+", "", text)
            # Remove email addresses
            text = re.sub(r"\S+@\S+\.\S+", "", text)
            # Remove bracketed annotations like [source] or (see above)
            text = self._BRACKET_CONTENT_RE.sub("", text)
            # Remove leading punctuation artefacts
            text = self._LEADING_PUNCT_RE.sub("", text)

        return text

    # ── Hindi normalization ───────────────────────────────────────────────────

    def _normalize_hindi(self, text: str) -> str:
        # Convert Devanagari danda → period
        text = self._DANDA_RE.sub(".", text)

        # Normalise nukta characters (ड़ ढ़ etc.) — ensure composed form
        # Replace common OCR-error zero-width non-joiner
        text = text.replace("\u200c", "").replace("\u200d", "")

        # Normalize common Hinglish contractions and spellings
        text = re.sub(r"\bnahin\b", "nahi", text, flags=re.IGNORECASE)
        text = re.sub(r"\bnaheen\b", "nahi", text, flags=re.IGNORECASE)
        text = re.sub(r"\bkyon\b", "kyun", text, flags=re.IGNORECASE)
        text = re.sub(r"\bhain\b", "hai", text, flags=re.IGNORECASE)

        # Sentence boundary — ensure space after period if followed by uppercase
        text = re.sub(r"\.([A-Z\u0900-\u097F])", r". \1", text)

        # Number hints for Hindi TTS (digits 0-9 → Devanagari hints)
        def _hi_num(m: re.Match) -> str:
            n = int(m.group())
            if 0 <= n <= 9:
                return self._HI_DIGITS[n]
            return m.group()  # leave larger numbers as-is; Piper handles them

        text = self._NUMBER_RE.sub(_hi_num, text)

        return text

    # ── Gujarati normalization ────────────────────────────────────────────────

    def _normalize_gujarati(self, text: str) -> str:
        # Convert danda → period
        text = self._GU_DANDA_RE.sub(".", text)

        # Remove zero-width characters
        text = text.replace("\u200c", "").replace("\u200d", "")

        # Common Gujlish normalizations
        text = re.sub(r"\bnaathi\b", "nathi", text, flags=re.IGNORECASE)
        text = re.sub(r"\bchhhe\b", "chhe", text, flags=re.IGNORECASE)
        text = re.sub(r"\bkaisun\b", "kaisu", text, flags=re.IGNORECASE)
        text = re.sub(r"\bkaison\b", "kaiso", text, flags=re.IGNORECASE)

        # Ensure Gujarati vowel signs are in composed form (NFC already handles this)
        # Sentence boundary
        text = re.sub(r"\.([A-Z\u0A80-\u0AFF])", r". \1", text)

        return text

    # ── English normalization ─────────────────────────────────────────────────

    def _normalize_english(self, text: str) -> str:
        # Expand common contractions for cleaner TTS
        contractions = {
            r"\bwon't\b": "will not",
            r"\bcan't\b": "cannot",
            r"\bdon't\b": "do not",
            r"\bdidn't\b": "did not",
            r"\bisn't\b": "is not",
            r"\baren't\b": "are not",
            r"\bwasn't\b": "was not",
            r"\bweren't\b": "were not",
            r"\bhasn't\b": "has not",
            r"\bhaven't\b": "have not",
            r"\bhadn't\b": "had not",
            r"\bwouldn't\b": "would not",
            r"\bshouldn't\b": "should not",
            r"\bcouldn't\b": "could not",
            r"\bmightn't\b": "might not",
            r"\bmustn't\b": "must not",
            r"\bI'm\b": "I am",
            r"\bI've\b": "I have",
            r"\bI'll\b": "I will",
            r"\bI'd\b": "I would",
            r"\bhe's\b": "he is",
            r"\bshe's\b": "she is",
            r"\bit's\b": "it is",
            r"\bwe're\b": "we are",
            r"\bthey're\b": "they are",
            r"\bwe've\b": "we have",
            r"\bthey've\b": "they have",
            r"\bthat's\b": "that is",
            r"\bwhat's\b": "what is",
            r"\bthere's\b": "there is",
        }
        for pattern, replacement in contractions.items():
            text = re.sub(pattern, replacement, text, flags=re.IGNORECASE)

        # Normalize ellipsis
        text = re.sub(r"\.{2,}", "…", text)

        return text

    # ── Final cleanup (all languages) ─────────────────────────────────────────

    def _final_cleanup(self, text: str) -> str:
        # Collapse multiple spaces
        text = self._MULTI_SPACE_RE.sub(" ", text)
        # Collapse multiple newlines
        text = self._MULTI_NEWLINE_RE.sub("\n\n", text)
        # Fix spacing around punctuation
        text = re.sub(r"\s+([,.!?;:])", r"\1", text)
        text = re.sub(r"([,.!?;:])(?=[^\s])", r"\1 ", text)
        # Strip leading/trailing whitespace
        text = text.strip()
        return text


# ── Convenience singleton ─────────────────────────────────────────────────────

_normalizer: Optional[TextNormalizer] = None


def get_normalizer() -> TextNormalizer:
    global _normalizer
    if _normalizer is None:
        _normalizer = TextNormalizer()
    return _normalizer


def normalize_for_tts(text: str, language: str) -> str:
    """Normalize text for TTS output in the given language."""
    return get_normalizer().normalize(text, language, for_tts=True)


def normalize_transcript(text: str, language: str) -> str:
    """Normalize a transcription result for display/memory storage."""
    return get_normalizer().normalize(text, language, for_tts=False)
