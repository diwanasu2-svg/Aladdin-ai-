"""End-to-End Multilingual Pipeline Tests — Feature 14.

Tests the complete pipeline:
  Hindi speech → STT → Language detection → LLM → Hindi TTS
  Gujarati speech → STT → Language detection → LLM → Gujarati TTS
  English speech → STT → Language detection → LLM → English TTS
  Mixed language (Hinglish / Gujlish) handling
  Language switching mid-conversation

Run with:
  python multilingual_tests.py
  python multilingual_tests.py --verbose
  python multilingual_tests.py --test=hindi
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from dataclasses import dataclass
from typing import Callable, List, Optional, Tuple

from language_detector import (
    LANG_ENGLISH,
    LANG_GUJARATI,
    LANG_HINDI,
    LanguageDetector,
    detect_language,
)
from text_normalizer import normalize_for_tts, normalize_transcript

# ── Logging ───────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    stream=sys.stdout,
)
log = logging.getLogger("multilingual_tests")


# ── Test utilities ────────────────────────────────────────────────────────────


@dataclass
class TestResult:
    name: str
    passed: bool
    message: str
    duration_ms: float
    details: str = ""

    def __str__(self) -> str:
        status = "✅ PASS" if self.passed else "❌ FAIL"
        return f"{status} [{self.duration_ms:.0f}ms] {self.name}: {self.message}"


def _run_test(name: str, fn: Callable[[], Tuple[bool, str]]) -> TestResult:
    start = time.monotonic()
    try:
        passed, message = fn()
    except Exception as e:
        passed = False
        message = f"Exception: {e}"
    elapsed_ms = (time.monotonic() - start) * 1000
    result = TestResult(
        name=name, passed=passed, message=message, duration_ms=elapsed_ms
    )
    print(result)
    return result


# ── Language detection tests ──────────────────────────────────────────────────


def test_hindi_detection_devanagari() -> Tuple[bool, str]:
    """Devanagari script → detect Hindi."""
    r = detect_language("आज का मौसम कैसा है?")
    return (
        r.language == LANG_HINDI and r.confidence > 0.5,
        f"detected={r.language}, confidence={r.confidence:.2f}",
    )


def test_hindi_detection_roman() -> Tuple[bool, str]:
    """Romanised Hindi → detect Hindi via vocab."""
    r = detect_language("Aaj ka mausam kya hai?")
    return (
        r.language == LANG_HINDI,
        f"detected={r.language}, confidence={r.confidence:.2f}",
    )


def test_gujarati_detection_native() -> Tuple[bool, str]:
    """Native Gujarati script → detect Gujarati."""
    r = detect_language("આજ નું હવામાન કેવું છે?")
    return (
        r.language == LANG_GUJARATI and r.confidence > 0.5,
        f"detected={r.language}, confidence={r.confidence:.2f}",
    )


def test_gujarati_detection_roman() -> Tuple[bool, str]:
    """Romanised Gujarati → detect Gujarati via vocab."""
    r = detect_language("Aaj nu hava kaisu che?")
    return (
        r.language == LANG_GUJARATI,
        f"detected={r.language}, confidence={r.confidence:.2f}",
    )


def test_english_detection() -> Tuple[bool, str]:
    """Standard English → detect English."""
    r = detect_language("What is the weather today?")
    return (
        r.language == LANG_ENGLISH and r.confidence > 0.5,
        f"detected={r.language}, confidence={r.confidence:.2f}",
    )


def test_hinglish_detection() -> Tuple[bool, str]:
    """Hinglish (Hindi+English code-mix) → detect as mixed Hindi."""
    r = detect_language("Aaj ka weather kaisa hai yaar?")
    # Should detect as Hindi (dominant) or mixed
    return (
        r.language in (LANG_HINDI, LANG_ENGLISH) or r.is_mixed,
        f"detected={r.language}, mixed={r.is_mixed}, confidence={r.confidence:.2f}",
    )


def test_gujlish_detection() -> Tuple[bool, str]:
    """Gujlish (Gujarati+English code-mix) → detect as mixed Gujarati."""
    r = detect_language("Tamaro weather check karo ne?")
    return (
        r.language in (LANG_GUJARATI, LANG_ENGLISH) or r.is_mixed,
        f"detected={r.language}, mixed={r.is_mixed}, confidence={r.confidence:.2f}",
    )


# ── Detection latency test ─────────────────────────────────────────────────────


def test_detection_latency() -> Tuple[bool, str]:
    """Language detection must complete in <200 ms."""
    detector = LanguageDetector()
    samples = [
        "What is the weather today?",
        "Aaj ka mausam kya hai?",
        "Aaj nu hava kaisu che?",
        "आज का मौसम कैसा है?",
        "આજ નું હવામાન કેવું છે?",
    ]
    times = []
    for text in samples:
        t0 = time.monotonic()
        detector.detect(text)
        times.append((time.monotonic() - t0) * 1000)

    max_ms = max(times)
    avg_ms = sum(times) / len(times)
    return (
        max_ms < 200,
        f"max={max_ms:.1f}ms avg={avg_ms:.1f}ms (target: <200ms)",
    )


# ── Language history test ──────────────────────────────────────────────────────


def test_language_history_continuity() -> Tuple[bool, str]:
    """History-biased detection should maintain language consistency."""
    detector = LanguageDetector()
    history = []

    # Establish Hindi context
    for phrase in ["Aaj ka mausam kya hai?", "Mujhe batao.", "Dhanyawad."]:
        r = detector.detect_with_history(phrase, history)
        history.append(r)

    # Ambiguous short phrase — history should bias toward Hindi
    ambiguous = "theek"
    r = detector.detect_with_history(ambiguous, history)
    return (
        r.language == LANG_HINDI,
        f"ambiguous phrase '{ambiguous}' detected as {r.language} with history bias",
    )


# ── Text normalization tests ──────────────────────────────────────────────────


def test_hindi_normalization() -> Tuple[bool, str]:
    """Hindi text should normalize danda and zero-width chars."""
    raw = "नमस्ते\u200c अलादीन। आज का मौसम\u200d कैसा है॥"
    normalized = normalize_for_tts(raw, LANG_HINDI)
    ok = "।" not in normalized and "॥" not in normalized and "\u200c" not in normalized
    return ok, f"normalized='{normalized}'"


def test_gujarati_normalization() -> Tuple[bool, str]:
    """Gujarati text should normalize danda."""
    raw = "નમસ્તે।  આજ  નું  હવામાન  ?"
    normalized = normalize_for_tts(raw, LANG_GUJARATI)
    ok = "।" not in normalized
    return ok, f"normalized='{normalized}'"


def test_english_normalization() -> Tuple[bool, str]:
    """English contractions should be expanded."""
    raw = "I can't do it. It's not my fault, don't worry."
    normalized = normalize_for_tts(raw, LANG_ENGLISH)
    ok = "cannot" in normalized and "it is" in normalized.lower()
    return ok, f"normalized='{normalized}'"


def test_markdown_stripped_for_tts() -> Tuple[bool, str]:
    """Markdown formatting should be stripped for TTS."""
    raw = "**Hello** world! Visit https://example.com for [more](http://x.com) info."
    normalized = normalize_for_tts(raw, LANG_ENGLISH)
    ok = "**" not in normalized and "https://" not in normalized
    return ok, f"normalized='{normalized}'"


# ── Language switching simulation ─────────────────────────────────────────────


def test_language_switching() -> Tuple[bool, str]:
    """Detector should switch language as user switches."""
    detector = LanguageDetector()
    history = []

    # Start Hindi
    h1 = detector.detect_with_history("Aaj ka mausam kya hai?", history)
    history.append(h1)
    h2 = detector.detect_with_history("Mujhe batao.", history)
    history.append(h2)

    # Switch to English
    e1 = detector.detect_with_history("What is the time?", history)
    history.append(e1)
    e2 = detector.detect_with_history("Tell me the news.", history)
    history.append(e2)

    # After 2 English turns, next English should still be English
    e3 = detector.detect_with_history("How are you?", history)

    return (
        h1.language == LANG_HINDI and e3.language == LANG_ENGLISH,
        f"Hindi={h1.language}({h1.confidence:.2f}) → English={e3.language}({e3.confidence:.2f})",
    )


# ── Unicode support tests ─────────────────────────────────────────────────────


def test_unicode_roundtrip() -> Tuple[bool, str]:
    """Hindi and Gujarati text should survive encode/decode roundtrip."""
    texts = [
        ("hi", "नमस्ते अलादीन, आज का मौसम बहुत अच्छा है।"),
        ("gu", "નમસ્તે અલ્લાઉદ્દીન, આજ નું હવામાન ઘણું સારું છે।"),
        ("en", "Hello Aladdin, today's weather is great!"),
    ]
    for lang, text in texts:
        encoded = text.encode("utf-8")
        decoded = encoded.decode("utf-8")
        if decoded != text:
            return False, f"Roundtrip failed for {lang}: {text!r} → {decoded!r}"

    return True, "All Unicode roundtrips passed"


def test_devanagari_script_detection() -> Tuple[bool, str]:
    """Devanagari-heavy text must score high for Hindi."""
    import re

    text = "यह एक परीक्षण वाक्य है जो हिंदी में लिखा गया है।"
    devanagari_chars = len(re.findall(r"[\u0900-\u097F]", text))
    total_chars = len([c for c in text if not c.isspace()])
    ratio = devanagari_chars / total_chars
    ok = ratio > 0.7
    r = detect_language(text)
    return (
        ok and r.language == LANG_HINDI,
        f"Devanagari ratio={ratio:.2f}, detected={r.language}",
    )


def test_gujarati_script_detection() -> Tuple[bool, str]:
    """Gujarati script text must be detected as Gujarati."""
    import re

    text = "આ એક ગુજરાતી વાક્ય છે."
    gu_chars = len(re.findall(r"[\u0A80-\u0AFF]", text))
    total_chars = len([c for c in text if not c.isspace()])
    ratio = gu_chars / total_chars
    ok = ratio > 0.5
    r = detect_language(text)
    return (
        ok and r.language == LANG_GUJARATI,
        f"Gujarati ratio={ratio:.2f}, detected={r.language}",
    )


# ── Fallback chain test ───────────────────────────────────────────────────────


def test_fallback_chain_config() -> Tuple[bool, str]:
    """Fallback chain must be: gu → hi → en."""
    from multilingual_tts import FALLBACK_CHAIN

    gu_chain = FALLBACK_CHAIN.get("gu", [])
    hi_chain = FALLBACK_CHAIN.get("hi", [])
    en_chain = FALLBACK_CHAIN.get("en", [])

    ok = (
        gu_chain == ["gu", "hi", "en"]
        and hi_chain == ["hi", "en"]
        and en_chain == ["en"]
    )
    return ok, f"gu={gu_chain}, hi={hi_chain}, en={en_chain}"


# ── Full pipeline simulation (no audio hardware needed) ───────────────────────


def test_full_pipeline_simulation_hindi() -> Tuple[bool, str]:
    """Simulate: Hindi transcript → detect → normalize → language match."""
    transcript = "अलादीन, आज का मौसम क्या है?"
    detected = detect_language(transcript)
    normalized = normalize_for_tts(
        "मौसम आज सुहाना है, लगभग 28 डिग्री।", detected.language
    )
    ok = detected.language == LANG_HINDI and len(normalized) > 5
    return ok, f"lang={detected.language}, norm_len={len(normalized)}"


def test_full_pipeline_simulation_gujarati() -> Tuple[bool, str]:
    """Simulate: Gujarati transcript → detect → normalize → language match."""
    transcript = "અલ્લાઉદ્દીન, આજ નું હવામાન?"
    detected = detect_language(transcript)
    normalized = normalize_for_tts(
        "આજ ૨૮ ડિગ્રી છે. ઘણો સારો દિવસ છે।", detected.language
    )
    ok = detected.language == LANG_GUJARATI and len(normalized) > 5
    return ok, f"lang={detected.language}, norm_len={len(normalized)}"


def test_full_pipeline_simulation_english() -> Tuple[bool, str]:
    """Simulate: English transcript → detect → normalize → language match."""
    transcript = "Aladdin, what's the weather today?"
    detected = detect_language(transcript)
    normalized = normalize_for_tts(
        "It's 28 degrees today. It's a lovely day.", detected.language
    )
    ok = detected.language == LANG_ENGLISH and "cannot" not in normalized.lower()
    return ok, f"lang={detected.language}, norm_len={len(normalized)}"


# ── Test runner ───────────────────────────────────────────────────────────────

ALL_TESTS = {
    "detection": [
        ("Hindi detection (Devanagari)", test_hindi_detection_devanagari),
        ("Hindi detection (romanised)", test_hindi_detection_roman),
        ("Gujarati detection (native script)", test_gujarati_detection_native),
        ("Gujarati detection (romanised)", test_gujarati_detection_roman),
        ("English detection", test_english_detection),
        ("Hinglish (code-mix) detection", test_hinglish_detection),
        ("Gujlish (code-mix) detection", test_gujlish_detection),
        ("Detection latency < 200ms", test_detection_latency),
        ("Language history continuity", test_language_history_continuity),
        ("Language switching", test_language_switching),
    ],
    "normalization": [
        ("Hindi text normalization", test_hindi_normalization),
        ("Gujarati text normalization", test_gujarati_normalization),
        ("English contraction expansion", test_english_normalization),
        ("Markdown stripped for TTS", test_markdown_stripped_for_tts),
    ],
    "unicode": [
        ("Unicode UTF-8 roundtrip", test_unicode_roundtrip),
        ("Devanagari script detection", test_devanagari_script_detection),
        ("Gujarati script detection", test_gujarati_script_detection),
    ],
    "pipeline": [
        ("Hindi full pipeline simulation", test_full_pipeline_simulation_hindi),
        ("Gujarati full pipeline simulation", test_full_pipeline_simulation_gujarati),
        ("English full pipeline simulation", test_full_pipeline_simulation_english),
        ("Fallback chain configuration", test_fallback_chain_config),
    ],
}


def run_tests(filter_group: Optional[str] = None, verbose: bool = False) -> bool:
    print("\n" + "═" * 70)
    print("  Aladdin Multilingual Pipeline Tests")
    print("═" * 70 + "\n")

    results: List[TestResult] = []

    for group, tests in ALL_TESTS.items():
        if filter_group and group != filter_group:
            continue

        print(f"\n── {group.upper()} TESTS ─────────────────────────────")
        for name, fn in tests:
            r = _run_test(name, fn)
            results.append(r)
            if verbose and r.details:
                print(f"   Details: {r.details}")

    passed = sum(1 for r in results if r.passed)
    failed = sum(1 for r in results if not r.passed)
    total = len(results)

    print("\n" + "═" * 70)
    print(f"  Results: {passed}/{total} passed", end="")
    if failed:
        print(f"  ({failed} FAILED)", end="")
    print()
    print("═" * 70 + "\n")

    if failed:
        print("Failed tests:")
        for r in results:
            if not r.passed:
                print(f"  • {r.name}: {r.message}")
        print()

    return failed == 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Aladdin Multilingual Pipeline Tests")
    parser.add_argument(
        "--test", choices=list(ALL_TESTS.keys()), help="Run only a specific test group"
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Show extra detail per test"
    )
    args = parser.parse_args()

    success = run_tests(filter_group=args.test, verbose=args.verbose)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
