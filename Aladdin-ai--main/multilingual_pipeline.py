"""Multilingual Voice Pipeline — Full End-to-End Integration.

Orchestrates all multilingual features:
  Feature 3: Automatic language detection
  Feature 4: Same-language response
  Feature 7: Automatic TTS language switching
  Feature 9: Language fallback
  Feature 10: Multilingual conversation memory
  Feature 11: Unicode support
  Feature 12: Text normalization per language

Usage::

    pipeline = MultilingualPipeline.from_config(cfg)
    pipeline.start()
    # Then call process_utterance() from your STT callback:
    response_text = pipeline.process_utterance("Aaj ka mausam kya hai?")
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Callable, Iterator, List, Optional

from language_detector import (
    LANG_ENGLISH,
    LANG_GUJARATI,
    LANG_HINDI,
    LanguageDetector,
    DetectionResult,
    detect_language,
)
from multilingual_llm import MultilingualOllamaClient, get_llm_client
from multilingual_memory import MultilingualMemory, MultilingualMessage
from multilingual_tts import (
    MultilingualTTSConfig,
    MultilingualTTSEngine,
    get_tts_engine,
)
from text_normalizer import normalize_for_tts, normalize_transcript

log = logging.getLogger(__name__)


@dataclass
class PipelineConfig:
    """Top-level configuration for the multilingual pipeline."""

    ollama_host: str = "http://localhost:11434"
    ollama_model: str = "llama3.1"
    ollama_temperature: float = 0.7
    ollama_max_tokens: int = 512

    default_language: str = LANG_ENGLISH
    detection_history_window: int = 5
    detection_history_weight: float = 0.2

    memory_db_path: str = "data/multilingual_memory.sqlite"
    memory_context_window: int = 12

    tts_config: Optional[MultilingualTTSConfig] = None
    enable_tts: bool = True
    stream_tts: bool = True

    @classmethod
    def from_cfg(cls, cfg) -> "PipelineConfig":
        """Build from the project config object."""
        ol = getattr(cfg, "ollama", None)
        return cls(
            ollama_host=(
                getattr(ol, "host", "http://localhost:11434")
                if ol
                else "http://localhost:11434"
            ),
            ollama_model=getattr(ol, "model", "llama3.1") if ol else "llama3.1",
            ollama_temperature=getattr(ol, "temperature", 0.7) if ol else 0.7,
            ollama_max_tokens=getattr(ol, "max_tokens", 512) if ol else 512,
        )


@dataclass
class UtteranceResult:
    """Result of processing one user utterance through the pipeline."""

    transcript: str
    detected_language: DetectionResult
    response_text: str
    response_language: str
    tts_audio: Optional[bytes] = None
    fallback_used: bool = False
    fallback_from: str = ""
    fallback_to: str = ""
    processing_time_ms: float = 0.0

    @property
    def language_name(self) -> str:
        return self.detected_language.language_name


class MultilingualPipeline:
    """End-to-end multilingual voice pipeline.

    Call :meth:`process_utterance` with a transcript string.
    The pipeline:
      1. Detects language (Feature 3)
      2. Normalizes transcript (Feature 12)
      3. Stores in multilingual memory (Feature 10)
      4. Generates LLM response in detected language (Feature 2, 4)
      5. Normalizes response for TTS (Feature 12)
      6. Synthesizes TTS in same language (Feature 7)
      7. Handles fallbacks transparently (Feature 9)
    """

    def __init__(self, config: PipelineConfig):
        self.config = config

        # Language detection
        self._detector = LanguageDetector(
            default_language=config.default_language,
            historyWindow=config.detection_history_window,
            historyWeight=config.detection_history_weight,
        )

        # LLM client
        self._llm = MultilingualOllamaClient(
            host=config.ollama_host,
            model=config.ollama_model,
            temperature=config.ollama_temperature,
            max_tokens=config.ollama_max_tokens,
        )

        # Memory
        self._memory = MultilingualMemory(config.memory_db_path)

        # TTS
        self._tts: Optional[MultilingualTTSEngine] = None
        if config.enable_tts:
            self._tts = get_tts_engine(config.tts_config)

        # Callbacks
        self._on_language_detected: Optional[Callable[[DetectionResult], None]] = None
        self._on_response_ready: Optional[Callable[[str, str], None]] = None
        self._on_tts_fallback: Optional[Callable[[str, str, str], None]] = None

        log.info(
            "MultilingualPipeline initialized (model=%s, default_lang=%s)",
            config.ollama_model,
            config.default_language,
        )

    @classmethod
    def from_config(cls, cfg) -> "MultilingualPipeline":
        return cls(PipelineConfig.from_cfg(cfg))

    # ── Callbacks ─────────────────────────────────────────────────────────────

    def on_language_detected(self, fn: Callable[[DetectionResult], None]) -> None:
        self._on_language_detected = fn

    def on_response_ready(self, fn: Callable[[str, str], None]) -> None:
        """Callback(response_text, language_code)."""
        self._on_response_ready = fn

    def on_tts_fallback(self, fn: Callable[[str, str, str], None]) -> None:
        """Callback(original_lang, fallback_lang, reason)."""
        self._on_tts_fallback = fn

    # ── Pipeline ──────────────────────────────────────────────────────────────

    def process_utterance(self, transcript: str) -> UtteranceResult:
        """Process a user transcript through the full multilingual pipeline.

        Returns an :class:`UtteranceResult` with response text and audio.
        """
        import time

        t0 = time.monotonic()

        # Step 1: Detect language — Feature 3
        detected = self._detector.detect(transcript)
        log.info(
            "Language detected: %s (%.0f%%, method=%s)",
            detected,
            detected.confidence * 100,
            detected.method,
        )
        if self._on_language_detected:
            self._on_language_detected(detected)

        # Step 2: Normalize transcript — Feature 12, 11
        clean_transcript = normalize_transcript(transcript, detected.language)

        # Step 3: Store in multilingual memory — Feature 10
        self._memory.add_user(
            content=clean_transcript,
            language=detected.language,
            is_mixed=detected.is_mixed,
            confidence=detected.confidence,
        )

        # Step 4: Build conversation context from memory — Feature 10
        history = self._memory.get_context_for_llm(
            limit=self.config.memory_context_window,
            language=detected.language,
        )
        # Remove the last user message (already added, will be included in chat call)
        if (
            history
            and history[-1]["role"] == "user"
            and history[-1]["content"] == clean_transcript
        ):
            history = history[:-1]

        # Step 5: Generate response in detected language — Feature 2, 4
        raw_response = self._llm.chat(
            user_message=clean_transcript,
            language=detected.language,
            is_mixed=detected.is_mixed,
            conversation_history=history,
        )
        response_text = (
            raw_response if isinstance(raw_response, str) else "".join(raw_response)
        )
        response_language = detected.language  # respond in same language — Feature 4

        if self._on_response_ready:
            self._on_response_ready(response_text, response_language)

        # Step 6: Normalize response for TTS — Feature 12
        tts_text = normalize_for_tts(response_text, response_language)

        # Step 7: Store assistant response in memory — Feature 10
        self._memory.add_assistant(
            content=response_text,
            language=response_language,
            is_mixed=detected.is_mixed,
        )

        # Step 8: TTS synthesis with automatic language switching — Feature 7
        tts_audio: Optional[bytes] = None
        fallback_used = False
        fallback_from = ""
        fallback_to = ""

        if self._tts and tts_text:

            def _on_fallback(orig: str, actual: str, reason: str) -> None:
                nonlocal fallback_used, fallback_from, fallback_to
                fallback_used = True
                fallback_from = orig
                fallback_to = actual
                log.warning("TTS fallback: %s → %s (%s)", orig, actual, reason)
                if self._on_tts_fallback:
                    self._on_tts_fallback(orig, actual, reason)

            if self.config.stream_tts:
                audio_chunks = list(
                    self._tts.stream(
                        tts_text, response_language, on_fallback=_on_fallback
                    )
                )
                tts_audio = b"".join(audio_chunks) if audio_chunks else None
            else:
                tts_audio = (
                    self._tts.speak(
                        tts_text, response_language, on_fallback=_on_fallback
                    )
                    or None
                )

        elapsed_ms = (time.monotonic() - t0) * 1000

        return UtteranceResult(
            transcript=transcript,
            detected_language=detected,
            response_text=response_text,
            response_language=response_language,
            tts_audio=tts_audio,
            fallback_used=fallback_used,
            fallback_from=fallback_from,
            fallback_to=fallback_to,
            processing_time_ms=elapsed_ms,
        )

    def process_utterance_streaming(
        self, transcript: str
    ) -> tuple[DetectionResult, Iterator[str], Iterator[bytes]]:
        """Streaming variant — yields LLM text chunks and TTS audio chunks.

        Returns (detected_language, text_stream, audio_stream).
        Audio stream begins as text streams in (token-by-token latency).
        """
        detected = self._detector.detect(transcript)
        clean = normalize_transcript(transcript, detected.language)
        self._memory.add_user(
            clean, detected.language, detected.is_mixed, detected.confidence
        )
        history = self._memory.get_context_for_llm(self.config.memory_context_window)

        text_stream = self._llm.chat(
            clean, detected.language, detected.is_mixed, history, stream=True
        )
        collected: list[str] = []

        def _text_with_memory():
            for chunk in (
                text_stream if hasattr(text_stream, "__iter__") else [text_stream]
            ):
                collected.append(chunk)
                yield chunk
            full = "".join(collected)
            self._memory.add_assistant(full, detected.language, detected.is_mixed)

        def _audio_stream():
            if not self._tts:
                return
            full = "".join(collected) if collected else ""
            if not full:
                return
            tts_text = normalize_for_tts(full, detected.language)
            yield from self._tts.stream(tts_text, detected.language)

        return detected, _text_with_memory(), _audio_stream()

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    def start(self) -> None:
        """Preload TTS voices and warm up the pipeline."""
        if self._tts:
            self._tts.preload()
        log.info("MultilingualPipeline started")

    def stop(self) -> None:
        self._memory.close()
        log.info("MultilingualPipeline stopped")

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *_):
        self.stop()

    # ── Diagnostics ───────────────────────────────────────────────────────────

    def status(self) -> dict:
        """Return pipeline health and configuration status."""
        llm_ok = self._llm.health_check()
        tts_langs = self._tts.available_languages() if self._tts else []
        return {
            "llm_healthy": llm_ok,
            "llm_model": self.config.ollama_model,
            "default_language": self.config.default_language,
            "tts_available_languages": tts_langs,
            "memory_stats": self._memory.language_stats(),
            "detection_history": self.config.detection_history_window,
        }
