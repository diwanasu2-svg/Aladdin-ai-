"""Continuous audio streaming with integrated wake word detection.

Phase 1 Voice Core — enhanced:
  - Low-CPU always-on ring-buffer capture (sounddevice InputStream)
  - Integrated WakeWordEngine with energy gate and debounce
  - Configurable chunk size, device, gain, noise suppression
  - Exponential-backoff reconnection on device/stream errors
  - Handles mic unavailable, permission errors, device changes
  - Structured logging for all state transitions
  - Thread-safe start/stop with graceful shutdown
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from collections import deque
from typing import Callable, Optional

import numpy as np
import sounddevice as sd

from .wake_word_engine import DetectionResult, WakeWordEngine, EngineState

log = logging.getLogger(__name__)


class AudioStreamManager:
    """Manages always-on microphone input with integrated wake word detection.

    Args:
        sample_rate:        Input sample rate in Hz (default 16 000).
        channels:           Number of input channels (1 = mono).
        chunk_size:         Samples per InputStream block (~32 ms at 16 kHz).
        device:             PortAudio device index (None = system default).
        enable_wake_word:   Enable wake word detection on the stream.
        wake_word_config:   Dict passed to WakeWordEngine constructor.
                            Keys: wake_words, sensitivity, threshold, cooldown,
                                  session_timeout, mic_gain,
                                  noise_suppression_level.
    """

    # Reconnection limits
    _MAX_RECONNECT_ATTEMPTS = 8
    _BASE_RECONNECT_DELAY = 1.0  # seconds; doubles each attempt, cap 30 s
    _QUEUE_MAXSIZE = 200  # ~200 × 32 ms ≈ 6.4 s of buffering

    def __init__(
        self,
        sample_rate: int = 16000,
        channels: int = 1,
        chunk_size: int = 512,
        device: Optional[int] = None,
        enable_wake_word: bool = True,
        wake_word_config: Optional[dict] = None,
    ) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self.chunk_size = chunk_size
        self.device = device
        self.enable_wake_word = enable_wake_word

        # -- Wake word engine --------------------------------------------------
        ww_cfg = wake_word_config or {}
        self.wake_word_engine: Optional[WakeWordEngine] = None
        if enable_wake_word:
            self.wake_word_engine = WakeWordEngine(
                wake_words=ww_cfg.get(
                    "wake_words", [ww_cfg.get("wake_word", "aladdin"), "hey_aladdin"]
                ),
                sensitivity=ww_cfg.get("sensitivity", "balanced"),
                threshold=ww_cfg.get("threshold", None),
                cooldown=ww_cfg.get("cooldown", 2.0),
                session_timeout=ww_cfg.get("session_timeout", 10.0),
                mic_gain=ww_cfg.get("mic_gain", 1.0),
                noise_suppression_level=ww_cfg.get("noise_suppression_level", 1),
                sample_rate=sample_rate,
            )
            # Wire engine callbacks to stream-level callbacks
            self.wake_word_engine.on_detected(self._engine_on_detected)
            self.wake_word_engine.on_rejected(self._engine_on_rejected)
            self.wake_word_engine.on_timeout(self._engine_on_timeout)
            self.wake_word_engine.on_error(self._engine_on_error)

        # -- Streaming state ---------------------------------------------------
        self._audio_queue: queue.Queue = queue.Queue(maxsize=self._QUEUE_MAXSIZE)
        self._stream: Optional[sd.InputStream] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._lock = threading.RLock()

        # Rolling audio buffer (2 s) fed to the wake word engine
        _buf_samples = int(2.0 * sample_rate)
        self._audio_buffer: deque = deque(maxlen=_buf_samples)

        # Reconnection state
        self._reconnect_attempts = 0

        # Power-save duty cycling (used by ContinuousListeningController
        # for sleep/idle modes). When > 1, only 1 in N chunks is fed to the
        # wake word detector — the microphone itself keeps capturing every
        # chunk so no audio is lost and wake word detection stays "active",
        # just at a reduced inference rate to save CPU/battery.
        self._power_save_duty_cycle = 1
        self._chunk_counter = 0

        # -- User callbacks ----------------------------------------------------
        self._on_audio_chunk: Optional[Callable[[np.ndarray], None]] = None
        self._on_wake_word_detected: Optional[Callable[[DetectionResult], None]] = None
        self._on_wake_word_rejected: Optional[Callable[[DetectionResult], None]] = None
        self._on_stream_error: Optional[Callable[[Exception], None]] = None
        self._on_stream_restart: Optional[Callable[[], None]] = None

        log.info(
            "[AudioStream] init | rate=%d chunk=%d device=%s wake_word=%s",
            sample_rate,
            chunk_size,
            device,
            enable_wake_word,
        )

    # ------------------------------------------------------------------
    # Public control API
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start background audio streaming."""
        with self._lock:
            if self._running:
                log.debug("[AudioStream] already running, ignoring start()")
                return
            log.info("[AudioStream] starting")
            self._running = True
            self._reconnect_attempts = 0
            self._thread = threading.Thread(
                target=self._run_with_recovery,
                name="aladdin-audio-stream",
                daemon=True,
            )
            self._thread.start()

    def stop(self) -> None:
        """Stop background audio streaming and release the microphone."""
        with self._lock:
            if not self._running:
                return
            log.info("[AudioStream] stopping")
            self._running = False
            self._close_stream()

        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3.0)
        log.info("[AudioStream] stopped")

    def is_running(self) -> bool:
        """Return True if the stream thread is alive."""
        return self._running and (self._thread is not None and self._thread.is_alive())

    def get_audio_chunk(self, timeout: float = 1.0) -> Optional[np.ndarray]:
        """Pop the next audio chunk from the queue (blocking).

        Returns None on timeout.
        """
        try:
            return self._audio_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def reset_wake_word(self) -> None:
        """Reset wake word engine state (cooldown + session)."""
        if self.wake_word_engine:
            self.wake_word_engine.reset()
            log.info("[AudioStream] wake word engine reset")

    def update_wake_config(self, **kwargs) -> None:
        """Live-update wake word engine parameters.

        Accepts the same keyword args as WakeWordEngine.update_config().
        """
        if self.wake_word_engine:
            self.wake_word_engine.update_config(**kwargs)

    def set_power_save(self, duty_cycle: int) -> None:
        """Reduce wake-word inference rate for sleep/idle power saving.

        Args:
            duty_cycle: Run detection on 1 out of every N chunks.
                        1 = full rate (no power saving).
        """
        duty_cycle = max(1, int(duty_cycle))
        with self._lock:
            if duty_cycle != self._power_save_duty_cycle:
                log.info(
                    "[AudioStream] power-save duty cycle %d -> %d",
                    self._power_save_duty_cycle,
                    duty_cycle,
                )
            self._power_save_duty_cycle = duty_cycle
            self._chunk_counter = 0

    # ------------------------------------------------------------------
    # Callback registration
    # ------------------------------------------------------------------

    def set_on_audio_chunk(
        self, callback: Optional[Callable[[np.ndarray], None]]
    ) -> None:
        """Called with every ~32 ms audio chunk (float32 mono)."""
        self._on_audio_chunk = callback

    def set_on_wake_word_detected(
        self, callback: Optional[Callable[[DetectionResult], None]]
    ) -> None:
        """Called when a wake word is confirmed."""
        self._on_wake_word_detected = callback

    def set_on_wake_word_rejected(
        self, callback: Optional[Callable[[DetectionResult], None]]
    ) -> None:
        """Called when a potential wake word is filtered as a false trigger."""
        self._on_wake_word_rejected = callback

    def set_on_stream_error(
        self, callback: Optional[Callable[[Exception], None]]
    ) -> None:
        """Called on unrecoverable stream errors."""
        self._on_stream_error = callback

    def set_on_stream_restart(self, callback: Optional[Callable[[], None]]) -> None:
        """Called each time the stream successfully reconnects after an error."""
        self._on_stream_restart = callback

    # ------------------------------------------------------------------
    # Internal engine callbacks
    # ------------------------------------------------------------------

    def _engine_on_detected(self, result: DetectionResult) -> None:
        if self._on_wake_word_detected:
            try:
                self._on_wake_word_detected(result)
            except Exception as exc:
                log.error("[AudioStream] on_wake_word_detected callback error: %s", exc)

    def _engine_on_rejected(self, result: DetectionResult) -> None:
        log.debug(
            "[AudioStream] false trigger rejected | word=%s conf=%.3f reason=%s",
            result.word,
            result.confidence,
            result.reason,
        )
        if self._on_wake_word_rejected:
            try:
                self._on_wake_word_rejected(result)
            except Exception as exc:
                log.error("[AudioStream] on_wake_word_rejected callback error: %s", exc)

    def _engine_on_timeout(self) -> None:
        log.info("[AudioStream] wake session timed out — listener reset")

    def _engine_on_error(self, exc: Exception) -> None:
        log.error("[AudioStream] wake word engine error: %s", exc)

    # ------------------------------------------------------------------
    # Stream lifecycle
    # ------------------------------------------------------------------

    def _run_with_recovery(self) -> None:
        """Top-level thread target — runs the stream with reconnection."""
        while self._running:
            try:
                if self._reconnect_attempts > 0:
                    log.info(
                        "[AudioStream] listener restart #%d", self._reconnect_attempts
                    )
                    if self._on_stream_restart:
                        try:
                            self._on_stream_restart()
                        except Exception:
                            pass

                self._reconnect_attempts = 0
                self._open_and_run_stream()

            except PermissionError as exc:
                log.error("[AudioStream] microphone permission denied: %s", exc)
                self._running = False
                if self._on_stream_error:
                    self._on_stream_error(exc)
                break

            except Exception as exc:
                log.error("[AudioStream] stream error: %s", exc)
                if not self._reconnect(exc):
                    break

        log.info("[AudioStream] stream thread exiting")

    def _open_and_run_stream(self) -> None:
        """Open the PortAudio stream and read chunks until stopped."""
        try:
            with sd.InputStream(
                device=self.device,
                samplerate=self.sample_rate,
                channels=self.channels,
                blocksize=self.chunk_size,
                dtype="float32",
                latency="low",
            ) as stream:
                self._stream = stream
                log.info(
                    "[AudioStream] stream open | device=%s rate=%d chunk=%d",
                    self.device,
                    self.sample_rate,
                    self.chunk_size,
                )

                while self._running:
                    self._read_chunk(stream)

        except sd.PortAudioError as exc:
            err_msg = str(exc).lower()
            if "invalid device" in err_msg or "no default input" in err_msg:
                log.error("[AudioStream] microphone unavailable: %s", exc)
                raise PermissionError(str(exc)) from exc
            raise
        finally:
            self._stream = None

    def _read_chunk(self, stream: sd.InputStream) -> None:
        """Read one chunk from the stream and process it."""
        try:
            data, overflowed = stream.read(self.chunk_size)
            if overflowed:
                log.debug("[AudioStream] input overflow — some audio dropped")
        except sd.PortAudioError as exc:
            log.error("[AudioStream] PortAudio read error: %s", exc)
            raise
        except Exception as exc:
            log.error("[AudioStream] unexpected read error: %s", exc)
            raise

        if data is None or len(data) == 0:
            return

        # Ensure mono float32
        chunk: np.ndarray = (
            np.mean(data, axis=1) if self.channels > 1 else data.flatten()
        ).astype(np.float32)

        # Queue for external consumers (drop if full — never block)
        try:
            self._audio_queue.put_nowait(chunk)
        except queue.Full:
            log.debug("[AudioStream] queue full — dropping chunk")

        # Update ring buffer
        self._audio_buffer.extend(chunk)

        # Chunk callback
        if self._on_audio_chunk:
            try:
                self._on_audio_chunk(chunk)
            except Exception as exc:
                log.error("[AudioStream] on_audio_chunk callback error: %s", exc)

        # Wake word detection — run on the rolling 2-second buffer.
        # Honour power-save duty cycling (sleep/idle modes): skip a
        # fraction of chunks to cut CPU/battery use while keeping wake
        # word monitoring "active" (mic capture above is unaffected).
        if (
            self.enable_wake_word
            and self.wake_word_engine
            and self.wake_word_engine.is_ready
        ):
            self._chunk_counter += 1
            if self._chunk_counter % self._power_save_duty_cycle == 0:
                buf = np.array(self._audio_buffer, dtype=np.float32)
                if len(buf) >= self.wake_word_engine._min_audio_samples:
                    self.wake_word_engine.detect(buf)

    def _close_stream(self) -> None:
        """Attempt to close the PortAudio stream."""
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception as exc:
                log.debug("[AudioStream] error closing stream: %s", exc)
            self._stream = None

    def _reconnect(self, exc: Exception) -> bool:
        """Attempt exponential-backoff reconnection.

        Returns True to continue retrying, False to give up.
        """
        self._reconnect_attempts += 1
        if self._reconnect_attempts > self._MAX_RECONNECT_ATTEMPTS:
            log.error(
                "[AudioStream] max reconnection attempts (%d) reached — giving up",
                self._MAX_RECONNECT_ATTEMPTS,
            )
            self._running = False
            if self._on_stream_error:
                self._on_stream_error(exc)
            return False

        delay = min(
            self._BASE_RECONNECT_DELAY * (2 ** (self._reconnect_attempts - 1)),
            30.0,
        )
        log.warning(
            "[AudioStream] reconnecting in %.1fs (attempt %d/%d)",
            delay,
            self._reconnect_attempts,
            self._MAX_RECONNECT_ATTEMPTS,
        )
        time.sleep(delay)
        return True
