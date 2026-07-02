"""Voice Activity Detection (VAD)."""

from __future__ import annotations

import logging
from typing import Optional

import numpy as np

log = logging.getLogger(__name__)

try:
    import webrtcvad
    _WEBRTC_AVAILABLE = True
except ImportError:
    _WEBRTC_AVAILABLE = False
    log.warning("webrtcvad not installed — using energy-based VAD fallback")


class VoiceActivityDetector:
    """VAD using webrtcvad (preferred) or energy threshold (fallback)."""

    def __init__(self, sample_rate: int = 16000, aggressiveness: int = 2) -> None:
        self._sample_rate = sample_rate
        self._aggressiveness = aggressiveness
        self._vad = None
        if _WEBRTC_AVAILABLE:
            self._vad = webrtcvad.Vad(aggressiveness)
            log.info("VAD: using webrtcvad (aggressiveness=%d)", aggressiveness)
        else:
            log.info("VAD: using energy-based fallback")

    def is_speech(self, audio_bytes: bytes, frame_duration_ms: int = 30) -> bool:
        """Return True if the audio frame contains speech."""
        if self._vad is not None:
            try:
                return self._vad.is_speech(audio_bytes, self._sample_rate)
            except Exception as exc:
                log.debug("webrtcvad error: %s — falling back to energy", exc)
        # Energy-based fallback
        return self._energy_vad(audio_bytes)

    def _energy_vad(self, audio_bytes: bytes, threshold: float = 300.0) -> bool:
        try:
            samples = np.frombuffer(audio_bytes, dtype=np.int16).astype(np.float32)
            rms = float(np.sqrt(np.mean(samples ** 2)))
            return rms > threshold
        except Exception:
            return True  # assume speech on error

    def process_stream(self, audio_chunks, silence_frames: int = 20):
        """Generator: yields (chunk, is_speech) pairs and stops after prolonged silence."""
        silence_count = 0
        for chunk in audio_chunks:
            speech = self.is_speech(chunk)
            yield chunk, speech
            if not speech:
                silence_count += 1
                if silence_count >= silence_frames:
                    log.debug("VAD: silence detected, stopping")
                    break
            else:
                silence_count = 0
