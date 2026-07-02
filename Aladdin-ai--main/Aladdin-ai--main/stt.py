"""Speech-to-text via OpenAI Whisper (local, no API key needed)."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional

from .config import WhisperCfg

log = logging.getLogger(__name__)

_model_cache: dict[str, object] = {}


class WhisperTranscriber:
    """Wraps OpenAI Whisper for local transcription."""

    def __init__(self, cfg: WhisperCfg):
        self.cfg = cfg
        cache_key = f"{cfg.model}:{cfg.device}"
        if cache_key not in _model_cache:
            try:
                import whisper
            except ImportError as e:
                raise ImportError(
                    "openai-whisper not installed. Run: pip install openai-whisper"
                ) from e
            log.info("Loading Whisper model %r on %s", cfg.model, cfg.device)
            _model_cache[cache_key] = whisper.load_model(cfg.model, device=cfg.device)
        self._model = _model_cache[cache_key]

    def transcribe(self, wav_path: str, language: Optional[str] = None) -> str:
        """Transcribe a WAV file to text."""
        lang = language or self.cfg.language
        try:
            result = self._model.transcribe(
                wav_path,
                language=lang,
                fp16=(self.cfg.device != "cpu"),
                verbose=False,
            )
            text = (result.get("text") or "").strip()
            log.debug("Transcribed: %r", text[:80])
            return text
        except Exception as exc:
            log.error("Whisper transcription failed: %s", exc)
            return ""

    def transcribe_stream(self, wav_path: str) -> str:
        """Alias for transcribe — future streaming hook."""
        return self.transcribe(wav_path)
