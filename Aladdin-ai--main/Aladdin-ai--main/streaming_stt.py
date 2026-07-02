"""
Phase 2 — Real Streaming STT
==============================
Continuous audio chunk processing with live partial transcription.
- <500 ms latency via ring-buffer processing
- Whisper streaming optimisation (FasterWhisper preferred)
- Incremental decoding with partial + final result callbacks
- Endpoint detection via silence / VAD
- Automatic language detection
- Streaming confidence scores
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Callable, List, Optional

import numpy as np

log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# Endpoint detection constants
_SILENCE_THRESHOLD_RMS = 0.01  # below this → silence
_SILENCE_HOLD_MS = 600  # ms of silence before endpoint declared
_MAX_UTTERANCE_S = 30  # hard cap on one utterance


class _SilenceEndpointDetector:
    """Simple RMS-based endpoint detector (used when VAD model unavailable)."""

    def __init__(self, sample_rate: int, hold_ms: int = _SILENCE_HOLD_MS):
        self.sample_rate = sample_rate
        self._hold_samples = int(hold_ms * sample_rate / 1000)
        self._silence_counter = 0
        self._speech_seen = False

    def feed(self, chunk: np.ndarray) -> bool:
        """Return True when endpoint detected."""
        rms = float(np.sqrt(np.mean(chunk**2)))
        if rms < _SILENCE_THRESHOLD_RMS:
            if self._speech_seen:
                self._silence_counter += len(chunk)
                if self._silence_counter >= self._hold_samples:
                    self._silence_counter = 0
                    return True
        else:
            self._speech_seen = True
            self._silence_counter = 0
        return False

    def reset(self):
        self._silence_counter = 0
        self._speech_seen = False


class StreamingSTT:
    """
    Streaming speech-to-text with partial transcription and endpoint detection.

    Usage
    -----
    stt = StreamingSTT(engine="faster-whisper", model="base")
    stt.set_on_partial_result(lambda t: log.info("partial:", t))
    stt.set_on_final_result(lambda t, lang, conf: log.info("final:", t, lang, conf))
    stt.start()
    stt.add_audio(chunk)   # feed raw float32 PCM
    stt.stop()
    """

    def __init__(
        self,
        sample_rate: int = 16000,
        engine: str = "faster-whisper",
        model: str = "base",
        device: str = "cpu",
        language: Optional[str] = None,
        partial_interval_ms: int = 400,
    ):
        self.sample_rate = sample_rate
        self.engine = engine.lower()
        self.model_name = model
        self.device = device
        self.language = language  # None → auto-detect
        self.partial_interval_ms = partial_interval_ms

        self._model = None  # lazy-loaded
        self._audio_ring: List[np.ndarray] = []
        self._ring_lock = threading.RLock()
        self._result_queue: "queue.Queue[str]" = queue.Queue()

        self._on_partial: Optional[Callable[[str], None]] = None
        self._on_final: Optional[Callable[[str, str, float], None]] = None

        self._running = False
        self._worker: Optional[threading.Thread] = None
        self._endpoint_detector = _SilenceEndpointDetector(sample_rate)

        self._load_model()

    # ── Lifecycle ─────────────────────────────────────────────────────────

    def start(self) -> None:
        """Start the streaming STT worker thread."""
        if self._running:
            return
        self._running = True
        self._endpoint_detector.reset()
        self._worker = threading.Thread(
            target=self._worker_loop, daemon=True, name="stt-worker"
        )
        self._worker.start()
        log.info(
            "StreamingSTT started (engine=%s, model=%s)", self.engine, self.model_name
        )

    def stop(self) -> None:
        """Stop the streaming STT worker."""
        self._running = False
        if self._worker:
            self._worker.join(timeout=5.0)
            self._worker = None
        log.info("StreamingSTT stopped")

    # ── Audio input ───────────────────────────────────────────────────────

    def add_audio(self, audio: np.ndarray) -> None:
        """Feed a raw float32 PCM chunk (mono, sample_rate Hz)."""
        with self._ring_lock:
            self._audio_ring.append(audio.flatten())

    # ── Callbacks ─────────────────────────────────────────────────────────

    def set_on_partial_result(self, callback: Optional[Callable[[str], None]]) -> None:
        self._on_partial = callback

    def set_on_final_result(
        self,
        callback: Optional[Callable[[str, str, float], None]],
    ) -> None:
        """Callback receives (text, detected_language, confidence)."""
        self._on_final = callback

    # ── Synchronous transcription (non-streaming) ─────────────────────────

    def transcribe_accumulated(self) -> str:
        """Drain the buffer and transcribe synchronously. Returns text."""
        with self._ring_lock:
            if not self._audio_ring:
                return ""
            audio = np.concatenate(self._audio_ring)
            self._audio_ring.clear()
        text, _, _ = self._transcribe(audio)
        return text

    # ── Private helpers ───────────────────────────────────────────────────

    def _load_model(self) -> None:
        if self.engine == "faster-whisper":
            self._load_faster_whisper()
        else:
            self._load_whisper()

    def _load_whisper(self) -> None:
        try:
            import whisper

            log.info(
                "Loading OpenAI Whisper '%s' on %s …", self.model_name, self.device
            )
            self._model = whisper.load_model(self.model_name, device=self.device)
            log.info("Whisper loaded.")
        except ImportError:
            log.error("openai-whisper not installed. Run: pip install openai-whisper")
        except Exception as exc:
            log.error("Whisper load error: %s", exc)

    def _load_faster_whisper(self) -> None:
        try:
            from faster_whisper import WhisperModel

            device = "cuda" if self.device == "cuda" else "cpu"
            ct2 = "float16" if device == "cuda" else "int8"
            log.info("Loading FasterWhisper '%s' on %s …", self.model_name, device)
            self._model = WhisperModel(self.model_name, device=device, compute_type=ct2)
            log.info("FasterWhisper loaded.")
        except ImportError:
            log.warning("faster-whisper not installed, falling back to openai-whisper")
            self.engine = "whisper"
            self._load_whisper()
        except Exception as exc:
            log.error("FasterWhisper load error: %s", exc)

    def _transcribe(self, audio: np.ndarray) -> tuple[str, str, float]:
        """Run model inference. Returns (text, language, confidence)."""
        if self._model is None:
            return "", "en", 0.0
        if len(audio) < self.sample_rate * 0.1:  # <100 ms → skip
            return "", "en", 0.0
        try:
            if self.engine == "faster-whisper":
                return self._transcribe_faster(audio)
            else:
                return self._transcribe_openai(audio)
        except Exception as exc:
            log.error("Transcription error: %s", exc)
            return "", "en", 0.0

    def _transcribe_faster(self, audio: np.ndarray) -> tuple[str, str, float]:
        segs, info = self._model.transcribe(
            audio,
            language=self.language,
            beam_size=5,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=300),
        )
        texts = []
        conf_total = 0.0
        count = 0
        for seg in segs:
            texts.append(seg.text)
            conf_total += getattr(seg, "avg_logprob", 0.0)
            count += 1
        text = " ".join(texts).strip()
        lang = getattr(info, "language", "en") or "en"
        confidence = (
            max(0.0, min(1.0, (conf_total / count + 5.0) / 5.0)) if count else 0.0
        )
        return text, lang, confidence

    def _transcribe_openai(self, audio: np.ndarray) -> tuple[str, str, float]:
        result = self._model.transcribe(
            audio,
            language=self.language,
            fp16=(self.device == "cuda"),
            verbose=False,
        )
        text = (result.get("text") or "").strip()
        lang = result.get("language") or "en"
        # Whisper doesn't expose per-word confidence easily; use 0.85 default
        return text, lang, 0.85

    def _worker_loop(self) -> None:
        """
        Background thread:
        1. Accumulate chunks into partial-transcription windows.
        2. Detect endpoints via silence.
        3. On endpoint, emit final transcription.
        4. Emit partial results at `partial_interval_ms` cadence.
        """
        interval = self.partial_interval_ms / 1000.0
        utterance_buf: List[np.ndarray] = []
        utterance_start = time.monotonic()
        last_partial = time.monotonic()

        while self._running:
            time.sleep(0.02)  # 20 ms poll

            with self._ring_lock:
                new_chunks = list(self._audio_ring)
                self._audio_ring.clear()

            if not new_chunks:
                continue

            for chunk in new_chunks:
                utterance_buf.append(chunk)
                endpoint = self._endpoint_detector.feed(chunk)
                hard_cap = (time.monotonic() - utterance_start) > _MAX_UTTERANCE_S

                if endpoint or hard_cap:
                    audio = np.concatenate(utterance_buf)
                    text, lang, conf = self._transcribe(audio)
                    if text:
                        log.info(
                            "STT final: '%s' (lang=%s conf=%.2f)", text[:80], lang, conf
                        )
                        if self._on_final:
                            try:
                                self._on_final(text, lang, conf)
                            except Exception as exc:
                                log.error("on_final callback error: %s", exc)
                        self._result_queue.put(text)
                    utterance_buf.clear()
                    utterance_start = time.monotonic()
                    self._endpoint_detector.reset()
                    last_partial = time.monotonic()

            # Emit partial
            if utterance_buf and (time.monotonic() - last_partial) >= interval:
                audio = np.concatenate(utterance_buf)
                partial, _, _ = self._transcribe(audio)
                if partial and self._on_partial:
                    try:
                        self._on_partial(partial)
                    except Exception as exc:
                        log.error("on_partial callback error: %s", exc)
                last_partial = time.monotonic()

    def get_partial_result(self, timeout: float = 0.1) -> Optional[str]:
        try:
            return self._result_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def reset(self) -> None:
        with self._ring_lock:
            self._audio_ring.clear()
        self._endpoint_detector.reset()
