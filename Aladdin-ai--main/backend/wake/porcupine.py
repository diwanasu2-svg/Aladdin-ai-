"""Picovoice Porcupine wake word engine."""
from __future__ import annotations
import logging
import struct
from typing import Callable, List, Optional

log = logging.getLogger(__name__)

try:
    import pvporcupine
    _PORCUPINE_AVAILABLE = True
except ImportError:
    _PORCUPINE_AVAILABLE = False
    log.warning("pvporcupine not installed")


class PorcupineDetector:
    """Detects wake words using Porcupine."""
    engine = "porcupine"

    def __init__(self, access_key: str = "", keywords: Optional[List[str]] = None,
                 keyword_paths: Optional[List[str]] = None,
                 sensitivities: Optional[List[float]] = None) -> None:
        self._access_key = access_key
        self._keywords = keywords or ["porcupine"]
        self._keyword_paths = keyword_paths
        self._sensitivities = sensitivities or [0.5] * len(self._keywords)
        self._handle = None
        self._running = False
        if _PORCUPINE_AVAILABLE and access_key:
            self._load()

    def _load(self):
        try:
            if self._keyword_paths:
                self._handle = pvporcupine.create(
                    access_key=self._access_key,
                    keyword_paths=self._keyword_paths,
                    sensitivities=self._sensitivities,
                )
            else:
                self._handle = pvporcupine.create(
                    access_key=self._access_key,
                    keywords=self._keywords,
                    sensitivities=self._sensitivities,
                )
            log.info("Porcupine loaded, keywords: %s", self._keywords)
        except Exception as exc:
            log.warning("Porcupine init failed: %s", exc)
            self._handle = None

    @property
    def available(self) -> bool:
        return _PORCUPINE_AVAILABLE and self._handle is not None

    @property
    def frame_length(self) -> int:
        if self._handle:
            return self._handle.frame_length
        return 512

    @property
    def sample_rate(self) -> int:
        if self._handle:
            return self._handle.sample_rate
        return 16000

    def detect(self, audio_chunk: bytes) -> Optional[str]:
        """Process one frame. Returns keyword if detected."""
        if not self.available:
            return None
        try:
            pcm = struct.unpack_from(f"{self.frame_length}h", audio_chunk)
            index = self._handle.process(pcm)
            if index >= 0:
                word = self._keywords[index] if index < len(self._keywords) else f"keyword_{index}"
                log.info("Porcupine detected: %s", word)
                return word
        except Exception as exc:
            log.debug("Porcupine detect error: %s", exc)
        return None

    def stop(self):
        self._running = False
        if self._handle:
            try:
                self._handle.delete()
            except Exception:
                pass
            self._handle = None
