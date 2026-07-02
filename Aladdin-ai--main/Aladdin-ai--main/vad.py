"""Voice Activity Detection (VAD) with Silero VAD (ONNX) or WebRTC fallback."""

from __future__ import annotations

import logging
from typing import Optional

import numpy as np

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# VAD confidence threshold — tune here or pass via constructor.
# ---------------------------------------------------------------------------
DEFAULT_THRESHOLD = 0.5
DEFAULT_MIN_SPEECH_MS = 100


class VoiceActivityDetector:
    """Detects speech in audio using Silero VAD (ONNX mode) or WebRTC fallback."""

    def __init__(
        self,
        sample_rate: int = 16000,
        engine: str = "silero",
        threshold: float = DEFAULT_THRESHOLD,
        min_speech_ms: int = DEFAULT_MIN_SPEECH_MS,
    ):
        """Initialise VAD.

        Args:
            sample_rate:   Audio sample rate (Hz).
            engine:        ``"silero"`` (ONNX) or ``"webrtc"``.
            threshold:     Confidence threshold in [0.0, 1.0].
            min_speech_ms: Minimum speech duration before reporting detection (ms).
        """
        self.sample_rate = sample_rate
        self.engine = engine.lower()
        self.threshold = max(0.0, min(1.0, threshold))
        self.min_speech_ms = max(0, min_speech_ms)
        self.min_speech_samples = int(self.min_speech_ms * sample_rate / 1000)

        self._vad_model = None
        self._webrtc_vad = None
        self._speech_buffer: list = []
        self._speech_start_sample: Optional[int] = None

        self._init_vad()

    # ── Initialisation ────────────────────────────────────────────────────

    def _init_vad(self) -> None:
        """Select and initialise the requested VAD engine."""
        if self.engine == "silero":
            self._init_silero_vad()
        elif self.engine == "webrtc":
            self._init_webrtc_vad()
        else:
            log.warning("Unknown VAD engine '%s'. Falling back to Silero.", self.engine)
            self._init_silero_vad()

    def _init_silero_vad(self) -> None:
        """Initialise Silero VAD in ONNX mode (single, consistent implementation)."""
        try:
            from silero_vad import load_silero_vad  # type: ignore

            log.info("Loading Silero VAD model (ONNX mode)")
            # FIX: removed conflicting onnx=False / force_onnx=True parameters.
            # Use a single consistent call with onnx=True for ONNX runtime.
            self._vad_model = load_silero_vad(onnx=True)
            log.info("Silero VAD loaded successfully (ONNX)")
        except ImportError:
            log.warning(
                "Silero VAD not installed. "
                "Install with: pip install silero-vad onnxruntime"
            )
            self._init_webrtc_vad()
        except Exception as exc:
            log.warning("Failed to load Silero VAD: %s. Falling back to WebRTC.", exc)
            self._init_webrtc_vad()

    def _init_webrtc_vad(self) -> None:
        """Initialise WebRTC VAD as fallback."""
        try:
            import webrtcvad  # type: ignore

            log.info("Loading WebRTC VAD (fallback)")
            self._webrtc_vad = webrtcvad.Vad(mode=2)  # 0–3, higher → stricter
            log.info("WebRTC VAD loaded successfully")
        except ImportError:
            log.warning(
                "WebRTC VAD not installed. " "Install with: pip install webrtcvad"
            )

    # ── Detection ─────────────────────────────────────────────────────────

    def detect(self, audio: np.ndarray) -> dict:
        """Detect speech in an audio chunk.

        Args:
            audio: Mono float32 audio, normalised to [-1, 1].

        Returns:
            Dict with keys:
                ``speech_detected`` (bool),
                ``confidence``      (float 0–1),
                ``speech_duration_ms`` (int).
        """
        result = {
            "speech_detected": False,
            "confidence": 0.0,
            "speech_duration_ms": 0,
        }

        try:
            if self._vad_model is not None:
                result = self._detect_silero(audio)
            elif self._webrtc_vad is not None:
                result = self._detect_webrtc(audio)
        except Exception as exc:
            log.error("VAD detection error: %s", exc)

        return result

    def _detect_silero(self, audio: np.ndarray) -> dict:
        """Detect speech with Silero VAD (ONNX)."""
        result = {
            "speech_detected": False,
            "confidence": 0.0,
            "speech_duration_ms": 0,
        }

        if len(audio) == 0:
            return result

        try:
            import torch  # type: ignore

            audio_tensor = torch.from_numpy(audio).float()
            confidence = float(self._vad_model(audio_tensor, self.sample_rate).item())
            result["confidence"] = confidence

            if confidence >= self.threshold:
                self._speech_buffer.extend(audio)
                duration_ms = int(len(self._speech_buffer) * 1000 / self.sample_rate)
                result["speech_duration_ms"] = duration_ms
                if len(self._speech_buffer) >= self.min_speech_samples:
                    result["speech_detected"] = True
            else:
                self._speech_buffer = []

        except Exception as exc:
            log.debug("Silero VAD inference error: %s", exc)

        return result

    def _detect_webrtc(self, audio: np.ndarray) -> dict:
        """Detect speech with WebRTC VAD."""
        result = {
            "speech_detected": False,
            "confidence": 0.0,
            "speech_duration_ms": 0,
        }

        if len(audio) == 0:
            return result

        try:
            audio_int16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
            frame_size = int(
                self.sample_rate * 0.01
            )  # 10 ms frames (WebRTC requirement)

            speech_frames = 0
            total_frames = 0

            for i in range(0, len(audio_int16) - frame_size, frame_size):
                frame = audio_int16[i : i + frame_size]
                if self._webrtc_vad.is_speech(frame.tobytes(), self.sample_rate):
                    speech_frames += 1
                total_frames += 1

            if total_frames > 0:
                confidence = speech_frames / total_frames
                result["confidence"] = confidence
                if confidence >= self.threshold:
                    duration_ms = int(total_frames * 10)
                    result["speech_duration_ms"] = duration_ms
                    result["speech_detected"] = duration_ms >= self.min_speech_ms

        except Exception as exc:
            log.debug("WebRTC VAD inference error: %s", exc)

        return result

    # ── Helpers ───────────────────────────────────────────────────────────

    def set_threshold(self, threshold: float) -> None:
        """Update confidence threshold at runtime."""
        self.threshold = max(0.0, min(1.0, threshold))
        log.debug("VAD threshold updated to %.2f", self.threshold)

    def reset(self) -> None:
        """Reset internal state (speech buffer, etc.)."""
        self._speech_buffer = []
        self._speech_start_sample = None
