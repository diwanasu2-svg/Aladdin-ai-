"""Audio I/O: recording, playback, and wake word detection.

Phase 1 Voice Core — updated to wire new config fields to AudioStreamManager
and WakeWordEngine, including:
  - wake_word_list, wake_sensitivity, wake_session_timeout
  - microphone_gain, noise_suppression_level
  - Structured logging for wake detected / timeout / restart / errors
  - Robust handling of mic unavailable, permission, and device change errors
"""

from __future__ import annotations

import logging
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Callable, Optional

import numpy as np
import sounddevice as sd
import soundfile as sf

from .audio_stream import AudioStreamManager
from .wake_word_engine import DetectionResult

log = logging.getLogger(__name__)


class AudioIO:
    """High-level audio I/O with wake word support."""

    def __init__(self, cfg) -> None:
        """Initialise audio I/O.

        Args:
            cfg: AudioCfg object with all audio settings.
        """
        self.cfg = cfg
        self.sample_rate = cfg.sample_rate
        self.is_playing = False
        self._playback_stop_event = threading.Event()

        # Build the wake word config dict from the richer Phase-1 settings
        wake_words = getattr(cfg, "wake_word_list", None) or None
        wake_word_config = {
            # Single / primary wake word (legacy compat)
            "wake_word": getattr(cfg, "wake_word", "aladdin"),
            # Extended list for multi-wake-word support
            "wake_words": wake_words
            or [
                getattr(cfg, "wake_word", "aladdin"),
                f"hey_{getattr(cfg, 'wake_word', 'aladdin')}",
            ],
            # Sensitivity profile
            "sensitivity": getattr(cfg, "wake_sensitivity", "balanced"),
            # Numeric threshold override (None = use profile default)
            "threshold": getattr(cfg, "wake_word_threshold", None),
            # Cooldown between accepted detections
            "cooldown": getattr(cfg, "wake_word_cooldown", 2.0),
            # Session timeout (seconds of silence before auto-reset)
            "session_timeout": getattr(cfg, "wake_session_timeout", 10.0),
            # Microphone gain
            "mic_gain": getattr(cfg, "microphone_gain", 1.0),
            # Noise suppression level
            "noise_suppression_level": getattr(cfg, "noise_suppression_level", 1),
        }

        self.stream_manager = AudioStreamManager(
            sample_rate=cfg.sample_rate,
            channels=1,
            chunk_size=getattr(cfg, "audio_chunk_size", 512),
            device=getattr(cfg, "microphone_device", None),
            enable_wake_word=(
                getattr(cfg, "wake_word_enabled", True)
                and getattr(cfg, "continuous_listening", True)
            ),
            wake_word_config=wake_word_config,
        )

        # Wire stream-level error / restart callbacks
        self.stream_manager.set_on_stream_error(self._on_stream_error)
        self.stream_manager.set_on_stream_restart(self._on_stream_restart)

        # Temporary WAV directory
        self._temp_dir = Path(tempfile.gettempdir()) / "aladdin_audio"
        self._temp_dir.mkdir(exist_ok=True)

        log.info(
            "[AudioIO] initialised | rate=%d wake=%s",
            cfg.sample_rate,
            getattr(cfg, "wake_word_enabled", True),
        )

    # ------------------------------------------------------------------
    # Listening control
    # ------------------------------------------------------------------

    def start_listening(self) -> None:
        """Start background continuous microphone listening."""
        if not getattr(self.cfg, "continuous_listening", True):
            log.debug("[AudioIO] continuous listening disabled")
            return
        if self.stream_manager.is_running():
            log.debug("[AudioIO] stream already running")
            return
        self.stream_manager.start()

    def stop_listening(self) -> None:
        """Stop background listening and release the microphone."""
        self.stream_manager.stop()

    def set_power_save(self, duty_cycle: int) -> None:
        """Throttle wake-word inference rate (used during sleep/idle)."""
        self.stream_manager.set_power_save(duty_cycle)

    # ------------------------------------------------------------------
    # Recording
    # ------------------------------------------------------------------

    def record_until_silence(self, timeout: Optional[float] = None) -> str:
        """Record from the microphone until silence is detected.

        Args:
            timeout: Hard cap on recording time (seconds).  None = use config.

        Returns:
            Absolute path to the recorded WAV file, or "" on error.
        """
        wav_path = self._temp_dir / f"rec_{int(time.time() * 1000)}.wav"
        max_secs = timeout or getattr(self.cfg, "max_seconds", 30.0)
        sil_thresh = getattr(self.cfg, "silence_threshold", 0.01)
        sil_secs = getattr(self.cfg, "silence_seconds", 1.2)
        sil_limit = int(sil_secs * self.sample_rate / 512)

        try:
            frames = sd.rec(
                int(max_secs * self.sample_rate),
                samplerate=self.sample_rate,
                channels=1,
                dtype="float32",
            )

            silence_frames = 0
            while True:
                if not sd.get_stream().active:
                    break
                rms = float(np.sqrt(np.mean(frames[-512:] ** 2)))
                if rms < sil_thresh:
                    silence_frames += 1
                else:
                    silence_frames = 0
                if silence_frames > sil_limit:
                    break
                time.sleep(0.01)

            sd.stop()
            sf.write(str(wav_path), frames, self.sample_rate)
            log.debug("[AudioIO] recorded to %s", wav_path)
            return str(wav_path)

        except Exception as exc:
            log.error("[AudioIO] recording error: %s", exc)
            return ""

    # ------------------------------------------------------------------
    # Playback
    # ------------------------------------------------------------------

    def play(self, wav_path: str) -> None:
        """Play a WAV file through the default output device."""
        try:
            data, sr = sf.read(wav_path, dtype="float32")

            if sr != self.sample_rate:
                try:
                    from scipy.signal import resample as sp_resample

                    n = int(len(data) * self.sample_rate / sr)
                    data = sp_resample(data, n)
                except ImportError:
                    pass  # play at original rate

            self.is_playing = True
            self._playback_stop_event.clear()

            with sd.OutputStream(
                samplerate=self.sample_rate,
                channels=1,
                dtype="float32",
            ) as stream:
                stream.write(data)
                time.sleep(len(data) / self.sample_rate)

        except Exception as exc:
            log.error("[AudioIO] playback error: %s", exc)
        finally:
            self.is_playing = False

    def stop_playback(self) -> None:
        """Interrupt active playback immediately."""
        self._playback_stop_event.set()
        self.is_playing = False
        sd.stop()

    # ------------------------------------------------------------------
    # Wake word wait helper
    # ------------------------------------------------------------------

    def wait_for_wake_word(
        self,
        timeout: Optional[float] = None,
        on_detected: Optional[Callable] = None,
    ) -> bool:
        """Block until a wake word is detected (or timeout expires).

        Args:
            timeout:     Maximum wait time in seconds (None = infinite).
            on_detected: Optional zero-arg callable fired on detection.

        Returns:
            True if detected, False on timeout.
        """
        if not getattr(self.cfg, "wake_word_enabled", True):
            log.warning("[AudioIO] wake word detection disabled")
            return False

        if not self.stream_manager.is_running():
            self.start_listening()

        detected_event = threading.Event()

        def _cb(result: DetectionResult) -> None:
            log.info(
                "[AudioIO] wake detected | word=%s conf=%.3f",
                result.word,
                result.confidence,
            )
            if on_detected:
                try:
                    on_detected()
                except Exception as exc:
                    log.error("[AudioIO] on_detected callback error: %s", exc)
            detected_event.set()

        self.stream_manager.set_on_wake_word_detected(_cb)

        try:
            return detected_event.wait(timeout=timeout)
        finally:
            # Clear callback so it doesn't fire again unexpectedly
            self.stream_manager.set_on_wake_word_detected(None)

    # ------------------------------------------------------------------
    # Error / restart callbacks
    # ------------------------------------------------------------------

    def _on_stream_error(self, exc: Exception) -> None:
        log.error("[AudioIO] unrecoverable stream error: %s", exc)

    def _on_stream_restart(self) -> None:
        log.info("[AudioIO] listener restarted after error")

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    def shutdown(self) -> None:
        """Release all audio resources."""
        self.stop_listening()
        self.stop_playback()
        try:
            import shutil

            shutil.rmtree(self._temp_dir, ignore_errors=True)
        except Exception as exc:
            log.debug("[AudioIO] could not clean temp dir: %s", exc)
        log.info("[AudioIO] shutdown complete")
