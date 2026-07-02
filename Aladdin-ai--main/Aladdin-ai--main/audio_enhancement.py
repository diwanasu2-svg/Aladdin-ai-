"""Audio enhancement: noise suppression, echo cancellation, etc."""

from __future__ import annotations

import logging
from typing import Optional

import numpy as np

log = logging.getLogger(__name__)


class NoiseSuppressionFilter:
    """Real-time microphone noise suppression."""

    def __init__(
        self,
        sample_rate: int = 16000,
        strength: int = 2,
    ):
        """Initialize noise suppression.

        Args:
            sample_rate: Audio sample rate (Hz)
            strength: 0=off, 1=light, 2=moderate, 3=aggressive
        """
        self.sample_rate = sample_rate
        self.strength = max(0, min(3, strength))
        self._ns_model = None

        if self.strength > 0:
            self._init_noise_suppression()

    def _init_noise_suppression(self) -> None:
        """Initialize noise suppression engine."""
        try:
            from noisereduce import Reducer

            log.info(f"Loading NoiseReduce with strength {self.strength}")
            # Strength mapping: 1=light, 2=moderate, 3=aggressive
            stationary = True
            prop_decrease = 0.5 + (self.strength - 1) * 0.15  # 0.5-0.8
            self._ns_model = {
                "stationary": stationary,
                "prop_decrease": prop_decrease,
            }
            log.info("Noise suppression initialized")
        except ImportError:
            log.warning(
                "noisereduce not installed. Install with: pip install noisereduce"
            )
            self.strength = 0

    def process(self, audio: np.ndarray) -> np.ndarray:
        """Apply noise suppression to audio chunk.

        Args:
            audio: Audio samples (mono, float32)

        Returns:
            Processed audio
        """
        if self.strength == 0 or len(audio) == 0:
            return audio

        try:
            if self._ns_model is not None:
                from noisereduce import reduce_noise

                # Apply noise reduction
                processed = reduce_noise(
                    y=audio,
                    sr=self.sample_rate,
                    stationary=self._ns_model["stationary"],
                    prop_decrease=self._ns_model["prop_decrease"],
                )
                return processed.astype(np.float32)
            else:
                # Fallback: simple spectral subtraction
                return self._simple_noise_gate(audio)

        except Exception as e:
            log.debug(f"Noise suppression error: {e}")
            return audio

    @staticmethod
    def _simple_noise_gate(audio: np.ndarray, gate_db: float = -40.0) -> np.ndarray:
        """Simple noise gate as fallback.

        Args:
            audio: Audio samples
            gate_db: Gate threshold in dB

        Returns:
            Gated audio
        """
        # Calculate RMS
        rms = np.sqrt(np.mean(audio**2))
        if rms < 1e-6:
            return audio

        # Convert to dB
        db = 20 * np.log10(np.abs(audio) / rms + 1e-10)

        # Gate
        gated = audio.copy()
        gated[db < gate_db] *= 0.5

        return gated.astype(np.float32)


class EchoCanceller:
    """Echo cancellation to prevent detecting TTS playback."""

    def __init__(
        self,
        sample_rate: int = 16000,
        timeout_ms: int = 100,
    ):
        """Initialize echo canceller.

        Args:
            sample_rate: Audio sample rate (Hz)
            timeout_ms: Echo buffer timeout (ms)
        """
        self.sample_rate = sample_rate
        self.timeout_ms = timeout_ms
        self.timeout_samples = int(timeout_ms * sample_rate / 1000)

        self._suppression_active = False
        self._playback_end_time = 0.0
        self._echo_buffer = []

    def set_playback_active(self, active: bool) -> None:
        """Mark playback as active or inactive.

        Args:
            active: True if TTS is playing
        """
        import time

        if active:
            self._suppression_active = True
            self._echo_buffer.clear()
            log.debug("Echo cancellation: playback active")
        else:
            # Stop active suppression, but keep buffering for timeout period
            self._suppression_active = False
            self._playback_end_time = time.time()
            log.debug(f"Echo cancellation: playback ended, timeout {self.timeout_ms}ms")

    def should_suppress(self) -> bool:
        """Check if microphone input should be suppressed.

        Returns:
            True if input should be suppressed
        """
        import time

        if self._suppression_active:
            return True

        # Check timeout
        elapsed_ms = (time.time() - self._playback_end_time) * 1000
        return elapsed_ms < self.timeout_ms

    def process(self, audio: np.ndarray) -> np.ndarray:
        """Apply echo cancellation to audio.

        Args:
            audio: Audio samples

        Returns:
            Processed audio (may be zeroed if suppressed)
        """
        if self.should_suppress():
            # Return silence during suppression
            return np.zeros_like(audio)

        return audio
