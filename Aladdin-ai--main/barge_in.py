"""
Unified Barge-In Manager
========================
Single implementation that replaces the duplicate logic previously split
across ``barge_in.py`` and ``full_duplex.py``.

Connects to:
- streaming_tts.py   (via ``tts_interrupt_fn`` callback)
- audio_pipeline.py  (provides ``process_mic_chunk``)
- main.py            (creates and owns the ``BargeInManager`` instance)

Pipeline integration:
    Microphone chunk
        → EchoCanceller.process()
        → BargeInDetector.detect()
        → (if barge-in) InterruptionHandler.signal_interrupt()
                        → tts_interrupt_fn()
                        → on_barge_in callback
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Callable, Optional

import numpy as np

log = logging.getLogger(__name__)

_CHUNK = 512
_RATE = 16000
_DEFAULT_THRESHOLD = 0.5
_DEFAULT_MIN_DURATION_MS = 150
_DEFAULT_BARGE_RMS = 0.05


# ── Echo canceller (moved from full_duplex.py — single source of truth) ──────


class EchoCanceller:
    """Adaptive echo canceller — subtracts a scaled speaker reference from mic."""

    def __init__(self, alpha: float = 0.15):
        self._alpha = alpha
        self._ref: Optional[np.ndarray] = None

    def set_reference(self, speaker_chunk: np.ndarray) -> None:
        """Feed latest speaker output as echo reference."""
        self._ref = speaker_chunk.copy()

    def process(self, mic_chunk: np.ndarray) -> np.ndarray:
        """Return echo-suppressed mic signal."""
        if self._ref is None or len(self._ref) != len(mic_chunk):
            return mic_chunk
        ref_power = float(np.dot(self._ref, self._ref)) + 1e-9
        cross = float(np.dot(mic_chunk, self._ref))
        gain = self._alpha * cross / ref_power
        return mic_chunk - gain * self._ref


# ── Core barge-in detector ────────────────────────────────────────────────────


class BargeInDetector:
    """Detects user speech during TTS playback.

    Supports two backends in priority order:
    1. Silero VAD (ONNX)  — high quality, model-based confidence score.
    2. RMS energy gate    — simple, zero-dependency fallback.
    """

    def __init__(
        self,
        sample_rate: int = _RATE,
        threshold: float = _DEFAULT_THRESHOLD,
        min_duration_ms: int = _DEFAULT_MIN_DURATION_MS,
        rms_threshold: float = _DEFAULT_BARGE_RMS,
    ):
        self.sample_rate = sample_rate
        self.threshold = max(0.0, min(1.0, threshold))
        self.min_duration_ms = min_duration_ms
        self.min_duration_samples = int(min_duration_ms * sample_rate / 1000)
        self.rms_threshold = rms_threshold

        self._speech_buffer: list = []
        self._vad_model = None
        self._init_vad()

    def _init_vad(self) -> None:
        """Load Silero VAD (ONNX). Falls back to RMS if unavailable."""
        try:
            from silero_vad import load_silero_vad  # type: ignore

            # Single consistent ONNX load — no conflicting parameters
            self._vad_model = load_silero_vad(onnx=True)
            log.info("BargeInDetector: Silero VAD (ONNX) loaded")
        except Exception as exc:
            log.warning(
                "BargeInDetector: Silero VAD unavailable (%s). "
                "Using RMS energy fallback.",
                exc,
            )

    def detect(self, audio: np.ndarray) -> dict:
        """Detect speech interruption in a mic chunk.

        Returns:
            Dict with ``interrupted`` (bool) and ``confidence`` (float).
        """
        result = {"interrupted": False, "confidence": 0.0}

        try:
            if self._vad_model is not None:
                result = self._detect_vad(audio)
            else:
                result = self._detect_rms(audio)
        except Exception as exc:
            log.error("BargeInDetector.detect error: %s", exc)

        return result

    def _detect_vad(self, audio: np.ndarray) -> dict:
        result = {"interrupted": False, "confidence": 0.0}
        if len(audio) == 0:
            return result
        try:
            import torch  # type: ignore

            tensor = torch.from_numpy(audio).float()
            confidence = float(self._vad_model(tensor, self.sample_rate).item())
            result["confidence"] = confidence

            if confidence >= self.threshold:
                self._speech_buffer.extend(audio)
                if len(self._speech_buffer) >= self.min_duration_samples:
                    result["interrupted"] = True
                    self._speech_buffer = []
                    log.info("BargeInDetector: barge-in (confidence=%.2f)", confidence)
            else:
                self._speech_buffer = []
        except Exception as exc:
            log.debug("BargeInDetector VAD inference error: %s", exc)
        return result

    def _detect_rms(self, audio: np.ndarray) -> dict:
        result = {"interrupted": False, "confidence": 0.0}
        if len(audio) == 0:
            return result
        rms = float(np.sqrt(np.mean(audio**2)))
        confidence = min(1.0, rms / max(self.rms_threshold, 1e-9))
        result["confidence"] = confidence
        if rms > self.rms_threshold:
            self._speech_buffer.extend(audio)
            if len(self._speech_buffer) >= self.min_duration_samples:
                result["interrupted"] = True
                self._speech_buffer = []
                log.info("BargeInDetector: barge-in (RMS=%.4f)", rms)
        else:
            self._speech_buffer = []
        return result

    def reset(self) -> None:
        """Clear detection state."""
        self._speech_buffer = []


# ── Interruption handler ──────────────────────────────────────────────────────


class InterruptionHandler:
    """Coordinates TTS cancellation when a barge-in is detected."""

    def __init__(self) -> None:
        self._stop_event = threading.Event()
        self._on_interrupted: Optional[Callable] = None

    def signal_interrupt(self) -> None:
        """Fire the interruption — stops playback and invokes callback."""
        log.info("InterruptionHandler: interrupt signalled")
        self._stop_event.set()
        if self._on_interrupted:
            try:
                self._on_interrupted()
            except Exception as exc:
                log.error("Interruption callback error: %s", exc)

    def is_interrupted(self) -> bool:
        return self._stop_event.is_set()

    def reset(self) -> None:
        self._stop_event.clear()

    def set_on_interrupted(self, callback: Optional[Callable]) -> None:
        self._on_interrupted = callback


# ── Unified BargeInManager — primary public API ───────────────────────────────


class BargeInManager:
    """
    Unified barge-in manager.

    Replaces the separate ``BargeInDetector``/``InterruptionHandler`` wiring
    from ``barge_in.py`` and the duplicate detection logic in ``full_duplex.py``.

    Usage::

        manager = BargeInManager()
        manager.set_tts_interrupt_fn(tts.interrupt)
        manager.set_on_barge_in(lambda: log.info("interrupted!"))
        manager.start()

        # In your audio loop:
        manager.process_mic_chunk(mic_audio)

        manager.stop()
    """

    def __init__(
        self,
        sample_rate: int = _RATE,
        channels: int = 1,
        chunk_size: int = _CHUNK,
        mic_device: Optional[int] = None,
        speaker_device: Optional[int] = None,
        enabled: bool = True,
        vad_threshold: float = _DEFAULT_THRESHOLD,
        barge_hold_ms: int = _DEFAULT_MIN_DURATION_MS,
        rms_threshold: float = _DEFAULT_BARGE_RMS,
    ):
        self.sample_rate = sample_rate
        self.channels = channels
        self.chunk_size = chunk_size
        self.mic_device = mic_device
        self.speaker_device = speaker_device
        self.enabled = enabled

        self._stream = None
        self._running = False
        self._lock = threading.RLock()
        self._playing = threading.Event()

        # Sub-components
        self._echo_canceller = EchoCanceller()
        try:
            from audio_enhancement import NoiseSuppressionFilter
            self._noise_suppression = NoiseSuppressionFilter(sample_rate=sample_rate)
        except ImportError:
            self._noise_suppression = None

        self._detector = BargeInDetector(
            sample_rate=sample_rate,
            threshold=vad_threshold,
            min_duration_ms=barge_hold_ms,
            rms_threshold=rms_threshold,
        )
        self._handler = InterruptionHandler()

        # Audio queues
        self._mic_queue: "queue.Queue[np.ndarray]" = queue.Queue(maxsize=200)
        self._speaker_queue: "queue.Queue[np.ndarray]" = queue.Queue(maxsize=200)

        # Callbacks
        self._on_mic_data: Optional[Callable[[np.ndarray], None]] = None
        self._tts_interrupt_fn: Optional[Callable] = None
        self._on_barge_in: Optional[Callable] = None

    # ── Lifecycle ─────────────────────────────────────────────────────────

    def start(self) -> None:
        """Open the full-duplex audio stream (requires sounddevice)."""
        if not self.enabled:
            log.debug("BargeInManager disabled — skipping stream open")
            return
        with self._lock:
            if self._running:
                return
            self._running = True
            try:
                import sounddevice as sd  # type: ignore

                self._stream = sd.Stream(
                    device=(self.mic_device, self.speaker_device),
                    samplerate=self.sample_rate,
                    channels=self.channels,
                    blocksize=self.chunk_size,
                    dtype="float32",
                    callback=self._audio_callback,
                    latency="low",
                )
                self._stream.start()
                log.info(
                    "BargeInManager: full-duplex stream started " "(rate=%d, chunk=%d)",
                    self.sample_rate,
                    self.chunk_size,
                )
            except ImportError:
                log.warning("sounddevice not installed — full-duplex unavailable")
                self._running = False
            except Exception as exc:
                log.error("BargeInManager: stream open failed: %s", exc)
                self._running = False

    def stop(self) -> None:
        """Close the audio stream."""
        with self._lock:
            if not self._running:
                return
            self._running = False
            if self._stream:
                try:
                    self._stream.stop()
                    self._stream.close()
                except Exception:
                    pass
                self._stream = None
        log.info("BargeInManager: stream stopped")

    # ── sounddevice callback (runs on audio thread) ───────────────────────

    def _audio_callback(
        self,
        indata: np.ndarray,
        outdata: np.ndarray,
        frames: int,
        time_info,
        status,
    ) -> None:
        if status:
            log.debug("BargeInManager audio status: %s", status)

        # ── Output (speaker playback) ────────────────────────────────────
        try:
            spk = self._speaker_queue.get_nowait()
            if len(spk) < frames:
                spk = np.pad(spk, (0, frames - len(spk)))
            outdata[:] = spk[:frames].reshape(outdata.shape)
            self._echo_canceller.set_reference(spk[:frames])
        except queue.Empty:
            outdata.fill(0)
            self._playing.clear()

        # ── Input (microphone) ───────────────────────────────────────────
        raw = indata.copy().flatten()
        
        # Apply noise suppression first if available
        if hasattr(self, '_noise_suppression') and self._noise_suppression:
            raw = self._noise_suppression.process(raw)
            
        cleaned = self._echo_canceller.process(raw)

        # Barge-in: only during TTS playback
        if self._playing.is_set():
            detection = self._detector.detect(cleaned)
            if detection["interrupted"]:
                self._trigger_barge_in()

        # Forward mic audio
        try:
            self._mic_queue.put_nowait(cleaned)
        except queue.Full:
            pass  # drop — latency priority

        if self._on_mic_data:
            try:
                self._on_mic_data(cleaned)
            except Exception:
                pass

    # ── External mic feed (when not using sounddevice stream) ────────────

    def process_mic_chunk(self, audio: np.ndarray) -> None:
        """Feed a mic chunk for barge-in detection.

        Call this from your own audio loop when you manage the stream externally.
        """
        if not self.enabled:
            return
        
        # Apply noise suppression first if available
        if hasattr(self, '_noise_suppression') and self._noise_suppression:
            audio = self._noise_suppression.process(audio)
            
        cleaned = self._echo_canceller.process(audio)
        if self._playing.is_set():
            detection = self._detector.detect(cleaned)
            if detection["interrupted"]:
                self._trigger_barge_in()
        try:
            self._mic_queue.put_nowait(cleaned)
        except queue.Full:
            pass

    # ── Playback API ──────────────────────────────────────────────────────

    def play_chunk(self, audio: np.ndarray) -> None:
        """Queue a float32 audio chunk for speaker output."""
        self._playing.set()
        try:
            self._speaker_queue.put(audio, timeout=1.0)
        except queue.Full:
            log.debug("BargeInManager: speaker queue full — dropping chunk")

    def play_stream(self, audio_gen) -> None:
        """Consume an audio generator for streaming TTS output."""
        for chunk in audio_gen:
            if not self._running:
                break
            self.play_chunk(chunk)

    def stop_playback(self) -> None:
        """Drain speaker queue and clear playing flag."""
        while not self._speaker_queue.empty():
            try:
                self._speaker_queue.get_nowait()
            except queue.Empty:
                break
        self._playing.clear()
        self._detector.reset()

    # ── Barge-in trigger ─────────────────────────────────────────────────

    def _trigger_barge_in(self) -> None:
        log.info("BargeInManager: barge-in — stopping TTS")
        self.stop_playback()
        if self._tts_interrupt_fn:
            try:
                self._tts_interrupt_fn()
            except Exception as exc:
                log.error("TTS interrupt fn error: %s", exc)
        if self._on_barge_in:
            try:
                self._on_barge_in()
            except Exception as exc:
                log.error("on_barge_in callback error: %s", exc)

    # ── Mic read ──────────────────────────────────────────────────────────

    def get_mic_chunk(self, timeout: float = 0.1) -> Optional[np.ndarray]:
        """Blocking read from the mic queue."""
        try:
            return self._mic_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    # ── Setters ───────────────────────────────────────────────────────────

    def set_tts_interrupt_fn(self, fn: Optional[Callable]) -> None:
        """Register the StreamingTTS.interrupt() method."""
        self._tts_interrupt_fn = fn
        self._handler.set_on_interrupted(fn)

    def set_on_barge_in(self, cb: Optional[Callable]) -> None:
        """Register a callback fired after TTS is interrupted."""
        self._on_barge_in = cb

    def set_on_mic_data(self, cb: Optional[Callable]) -> None:
        """Register a callback receiving every processed mic chunk."""
        self._on_mic_data = cb

    def set_speaker_reference(self, audio: np.ndarray) -> None:
        """Manually update echo reference (when managing playback externally)."""
        self._echo_canceller.set_reference(audio)

    @property
    def is_playing(self) -> bool:
        return self._playing.is_set()

    @property
    def is_running(self) -> bool:
        return self._running


# ── Backwards-compat shim — keeps old code that imports FullDuplexAudioManager working ──


class FullDuplexAudioManager(BargeInManager):
    """
    Deprecated: use ``BargeInManager`` directly.

    Retained as a thin alias so any existing imports of
    ``FullDuplexAudioManager`` from ``full_duplex.py`` continue to work
    without changes.
    """

    def __init__(self, *args, **kwargs):
        # Rename old param names to new ones if supplied
        if "barge_threshold" in kwargs:
            kwargs["rms_threshold"] = kwargs.pop("barge_threshold")
        if "barge_hold_ms" not in kwargs and "barge_hold_ms" in kwargs:
            pass  # already correct name
        super().__init__(*args, **kwargs)
        log.warning(
            "FullDuplexAudioManager is deprecated. "
            "Import BargeInManager from barge_in instead."
        )
