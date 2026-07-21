"""Browser-based wake word: config sent to frontend for Web Audio API detection."""
from __future__ import annotations
import json
import logging
from typing import Dict, List

log = logging.getLogger(__name__)


class BrowserWakeConfig:
    """Generates configuration for browser-side wake word detection via Web Audio API."""
    engine = "browser"

    def __init__(self, wake_words: Optional[List[str]] = None,
                 sensitivity: float = 0.7, sample_rate: int = 16000) -> None:
        from typing import Optional
        self._wake_words = wake_words or ["hey aladdin", "aladdin"]
        self._sensitivity = sensitivity
        self._sample_rate = sample_rate

    def get_config(self) -> Dict:
        return {
            "engine": self.engine,
            "wake_words": self._wake_words,
            "sensitivity": self._sensitivity,
            "sample_rate": self._sample_rate,
            "instructions": (
                "Use the Web Speech API (SpeechRecognition) to continuously listen. "
                "Send audio via WebSocket /ws/listen. "
                "Detect wake word client-side and signal server when triggered."
            ),
        }
