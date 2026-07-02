"""Text-to-speech — Piper (primary) with pyttsx3 fallback."""

from __future__ import annotations

import logging
import os
import subprocess
import tempfile
import wave
from pathlib import Path
from typing import Optional

from .config import PiperCfg

log = logging.getLogger(__name__)


class PiperSynthesizer:
    """Neural TTS via Piper. Falls back to pyttsx3 if Piper model not found."""

    def __init__(self, cfg: PiperCfg):
        self.cfg = cfg
        self._voice = None
        self._fallback = False

        if not cfg.enabled:
            self._fallback = True
            return

        model = Path(cfg.model_path)
        if not model.is_absolute():
            # Resolve relative to the aladdin/ project root
            model = Path(__file__).resolve().parent.parent / model

        if not model.exists():
            log.warning(
                "Piper model not found at %s — falling back to pyttsx3. "
                "Download from https://huggingface.co/rhasspy/piper-voices",
                model,
            )
            self._fallback = True
            return

        try:
            # Try vendored Piper first, then installed package
            try:
                from piper import PiperVoice  # type: ignore
            except ImportError:
                from piper.voice import PiperVoice  # type: ignore

            log.info("Loading Piper voice %s", model)
            self._voice = PiperVoice.load(
                str(model),
                config_path=cfg.config_path,
                use_cuda=False,
            )
        except Exception as exc:
            log.warning("Could not load Piper (%s) — falling back to pyttsx3.", exc)
            self._fallback = True

    def synthesize(self, text: str) -> str:
        """Synthesize text to a temp WAV file. Returns the path."""
        if self._fallback or self._voice is None:
            return self._synthesize_pyttsx3(text)
        return self._synthesize_piper(text)

    def _synthesize_piper(self, text: str) -> str:
        fd, path = tempfile.mkstemp(suffix=".wav", prefix="aladdin_tts_")
        os.close(fd)
        try:
            with wave.open(path, "wb") as wf:
                self._voice.synthesize(text, wf, speaker_id=self.cfg.speaker_id)
            log.debug("Piper synthesized %d chars → %s", len(text), path)
            return path
        except Exception as exc:
            log.error("Piper synthesis failed: %s", exc)
            return self._synthesize_pyttsx3(text)

    def _synthesize_pyttsx3(self, text: str) -> str:
        """Fallback TTS using pyttsx3."""
        fd, path = tempfile.mkstemp(suffix=".wav", prefix="aladdin_tts_fb_")
        os.close(fd)
        try:
            import pyttsx3

            engine = pyttsx3.init()
            engine.setProperty("rate", 175)
            engine.save_to_file(text, path)
            engine.runAndWait()
            log.debug("pyttsx3 synthesized → %s", path)
            return path
        except Exception as exc:
            log.error("pyttsx3 fallback failed: %s", exc)
            # Return empty wav so playback doesn't crash
            with wave.open(path, "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(22050)
                wf.writeframes(b"")
            return path
