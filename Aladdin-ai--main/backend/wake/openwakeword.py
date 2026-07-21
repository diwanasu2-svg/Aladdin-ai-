"""OpenWakeWord integration."""
from __future__ import annotations
import asyncio
import logging
import numpy as np
from typing import Callable, List, Optional

log = logging.getLogger(__name__)

try:
    import openwakeword
    from openwakeword.model import Model as OWWModel
    _OWW_AVAILABLE = True
except ImportError:
    _OWW_AVAILABLE = False
    log.warning("openwakeword not installed")


class OpenWakeWordDetector:
    """Detects wake words using OpenWakeWord models."""
    engine = "openwakeword"

    def __init__(self, model_paths: Optional[List[str]] = None,
                 threshold: float = 0.5, inference_framework: str = "onnx") -> None:
        self._threshold = threshold
        self._model_paths = model_paths or []
        self._model = None
        self._running = False
        if _OWW_AVAILABLE:
            try:
                if model_paths:
                    self._model = OWWModel(wakeword_models=model_paths,
                                           inference_framework=inference_framework)
                else:
                    self._model = OWWModel(inference_framework=inference_framework)
                log.info("OpenWakeWord model loaded, words: %s",
                         list(self._model.models.keys()) if self._model else [])
            except Exception as exc:
                log.warning("OpenWakeWord model load failed: %s", exc)

    @property
    def available(self) -> bool:
        return _OWW_AVAILABLE and self._model is not None

    def detect(self, audio_chunk: bytes, sample_rate: int = 16000) -> Optional[str]:
        """Process one audio chunk. Returns wake word name if detected, else None."""
        if not self.available:
            return None
        try:
            audio = np.frombuffer(audio_chunk, dtype=np.int16)
            self._model.predict(audio)
            scores = self._model.prediction_buffer
            for word, score_list in scores.items():
                if score_list and max(score_list) >= self._threshold:
                    log.info("Wake word detected: %s (score=%.2f)", word, max(score_list))
                    return word
        except Exception as exc:
            log.debug("OWW detect error: %s", exc)
        return None

    async def run_continuous(self, audio_stream, callback: Callable[[str], None]) -> None:
        """Run detection in a loop, calling callback on each detection."""
        self._running = True
        async for chunk in audio_stream:
            if not self._running:
                break
            word = self.detect(chunk)
            if word:
                callback(word)

    def stop(self):
        self._running = False
