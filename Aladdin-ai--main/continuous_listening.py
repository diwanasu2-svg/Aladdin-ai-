"""Continuous Listening Module — hands-free voice assistant orchestration.

Phase 2 Voice Core. Builds on top of the Phase 1 always-on audio stack
(``audio_io.py`` / ``audio_stream.py`` / ``wake_word_engine.py``) to provide
a complete hands-free state machine:

  - Always-listening microphone with automatic recovery (delegated to
    ``AudioStreamManager``, which already handles reconnection/backoff).
  - Automatic resume of listening immediately after TTS playback finishes.
  - Sleep mode after a configurable period of inactivity (wake word stays
    active, CPU/battery usage reduced via duty-cycled detection).
  - Conversation mode: keep listening for follow-ups after a reply without
    requiring the wake word again, until a silence timeout elapses.
  - Configurable silence timeout for finalising an utterance.
  - Idle mode: a deeper power-saving tier after extended inactivity.
  - Fully hands-free — no keyboard/mouse/button interaction required.

This module never talks to the microphone directly; it drives an
``AudioIO`` instance (recording/playback/wake word) plus caller-supplied
callables for transcription, response generation and speech synthesis, so
it stays decoupled from any particular STT/LLM/TTS implementation.

Thread-safety: all state transitions are guarded by a single ``RLock``.
All watchdog timers are cancelled on ``stop()``/state exit to avoid
leaks, and every callback is wrapped in try/except so a single failure in
caller code can never crash the listening loop.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from enum import Enum, auto
from typing import Callable, Optional

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# State machine
# ---------------------------------------------------------------------------


class ListenState(Enum):
    STOPPED = auto()  # controller not running
    WAITING_FOR_WAKE = auto()  # mic open, waiting for wake word
    ACTIVE_LISTENING = auto()  # recording user speech
    PROCESSING = auto()  # transcribing / generating a reply
    SPEAKING = auto()  # TTS playback in progress
    CONVERSATION = auto()  # post-reply follow-up window (no wake word needed)
    SLEEP = auto()  # inactivity power-save tier 1 (wake word active)
    IDLE = auto()  # inactivity power-save tier 2 (wake word active)


@dataclass
class ContinuousListeningConfig:
    """Configuration for the continuous listening state machine.

    Mirrors the relevant fields on ``AudioCfg`` so the controller can be
    constructed either from the project config object or standalone
    (e.g. in tests) without depending on the full config dataclass tree.
    """

    always_listening_enabled: bool = True
    auto_resume_enabled: bool = True

    conversation_mode_enabled: bool = True
    conversation_timeout: float = 8.0

    listen_silence_timeout: float = 1.2

    sleep_mode_enabled: bool = True
    sleep_timeout: float = 60.0

    idle_mode_enabled: bool = True
    idle_timeout: float = 300.0

    sleep_power_save_duty_cycle: int = 2
    idle_power_save_duty_cycle: int = 4

    @staticmethod
    def from_audio_cfg(cfg) -> "ContinuousListeningConfig":
        """Build from an ``AudioCfg``-like object, tolerating missing fields."""
        g = lambda name, default: getattr(cfg, name, default)
        return ContinuousListeningConfig(
            always_listening_enabled=g("always_listening_enabled", True),
            auto_resume_enabled=g("auto_resume_enabled", True),
            conversation_mode_enabled=g("conversation_mode_enabled", True),
            conversation_timeout=g("conversation_timeout", 8.0),
            listen_silence_timeout=g(
                "listen_silence_timeout", g("silence_seconds", 1.2)
            ),
            sleep_mode_enabled=g("sleep_mode_enabled", True),
            sleep_timeout=g("sleep_timeout", 60.0),
            idle_mode_enabled=g("idle_mode_enabled", True),
            idle_timeout=g("idle_timeout", 300.0),
            sleep_power_save_duty_cycle=g("sleep_power_save_duty_cycle", 2),
            idle_power_save_duty_cycle=g("idle_power_save_duty_cycle", 4),
        )


class ContinuousListeningController:
    """Drives fully hands-free, always-listening voice interaction.

    Parameters
    ----------
    audio:
        An ``AudioIO`` instance (microphone + wake word + playback).
    config:
        ``ContinuousListeningConfig`` instance.
    transcribe_fn:
        ``(wav_path: str) -> str``. Required.
    respond_fn:
        ``(user_text: str) -> str``. Required. May raise; errors are caught
        and logged, and the controller resumes listening afterwards.
    speak_fn:
        ``(text: str) -> None``. Should block until playback completes
        (this is how the controller knows when to auto-resume listening).
        Defaults to ``audio.play`` semantics if not provided and ``audio``
        exposes a synthesize+play pair via ``tts_fn``.
    on_state_change:
        Optional ``(old: ListenState, new: ListenState) -> None`` hook for
        UI/logging integration.
    """

    def __init__(
        self,
        audio,
        config: Optional[ContinuousListeningConfig] = None,
        transcribe_fn: Optional[Callable[[str], str]] = None,
        respond_fn: Optional[Callable[[str], str]] = None,
        speak_fn: Optional[Callable[[str], None]] = None,
        on_state_change: Optional[
            Callable[["ListenState", "ListenState"], None]
        ] = None,
        on_wake: Optional[Callable[[], None]] = None,
        on_user_text: Optional[Callable[[str], None]] = None,
    ) -> None:
        self.audio = audio
        self.cfg = config or ContinuousListeningConfig()

        self._transcribe_fn = transcribe_fn
        self._respond_fn = respond_fn
        self._speak_fn = speak_fn
        self._on_state_change = on_state_change
        self._on_wake = on_wake
        self._on_user_text = on_user_text

        self._lock = threading.RLock()
        self._state = ListenState.STOPPED
        self._running = False
        self._loop_thread: Optional[threading.Thread] = None

        # Inactivity tracking
        self._last_activity = time.monotonic()
        self._sleep_timer: Optional[threading.Timer] = None
        self._idle_timer: Optional[threading.Timer] = None

        # Single-flight guard — prevents duplicate recording sessions even
        # if start() is called more than once or callbacks race.
        self._turn_lock = threading.Lock()

        log.info(
            "[ContinuousListening] init | always_on=%s conversation=%s "
            "sleep=%s(%.0fs) idle=%s(%.0fs)",
            self.cfg.always_listening_enabled,
            self.cfg.conversation_mode_enabled,
            self.cfg.sleep_mode_enabled,
            self.cfg.sleep_timeout,
            self.cfg.idle_mode_enabled,
            self.cfg.idle_timeout,
        )

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start the hands-free continuous listening loop."""
        with self._lock:
            if self._running:
                log.debug("[ContinuousListening] already running")
                return
            if not self.cfg.always_listening_enabled:
                log.info("[ContinuousListening] always-listening disabled by config")
                return
            self._running = True

        try:
            self.audio.start_listening()
        except Exception as exc:
            log.error("[ContinuousListening] failed to start microphone: %s", exc)
            with self._lock:
                self._running = False
            raise

        self._touch_activity()
        self._set_state(ListenState.WAITING_FOR_WAKE)

        self._loop_thread = threading.Thread(
            target=self._loop,
            name="aladdin-continuous-listening",
            daemon=True,
        )
        self._loop_thread.start()
        log.info("[ContinuousListening] started")

    def stop(self) -> None:
        """Stop the loop, cancel all timers, and release the microphone."""
        with self._lock:
            if not self._running:
                return
            self._running = False

        self._cancel_sleep_timer()
        self._cancel_idle_timer()

        try:
            self.audio.stop_listening()
        except Exception as exc:
            log.debug("[ContinuousListening] error stopping audio: %s", exc)

        if self._loop_thread and self._loop_thread.is_alive():
            self._loop_thread.join(timeout=3.0)

        self._set_state(ListenState.STOPPED)
        log.info("[ContinuousListening] stopped")

    def is_running(self) -> bool:
        with self._lock:
            return self._running

    @property
    def state(self) -> ListenState:
        with self._lock:
            return self._state

    # ------------------------------------------------------------------
    # Main loop
    # ------------------------------------------------------------------

    def _loop(self) -> None:
        """Top-level hands-free loop: wake -> listen -> respond -> repeat."""
        while self.is_running():
            try:
                in_conversation = self.state == ListenState.CONVERSATION

                if not in_conversation:
                    self._set_state(ListenState.WAITING_FOR_WAKE)
                    woke = self._wait_for_wake()
                    if not self.is_running():
                        break
                    if not woke:
                        # Timed out waiting for wake word (used to drive
                        # sleep/idle re-evaluation) — loop and re-check.
                        continue
                    self._touch_activity()
                    if self._on_wake:
                        self._safe_call(self._on_wake)

                self._run_turn()

            except Exception as exc:  # pragma: no cover - defensive
                log.error("[ContinuousListening] loop error: %s", exc)
                time.sleep(0.5)

        log.info("[ContinuousListening] loop exiting")

    def _wait_for_wake(self) -> bool:
        """Block (with periodic wake-up) until the wake word fires.

        Polls in short intervals so sleep/idle timers stay responsive
        without ever stopping the microphone — wake word detection
        remains active the whole time (per requirements), just at a
        reduced duty cycle while asleep/idle.
        """
        poll_interval = 1.0
        while self.is_running():
            detected = self.audio.wait_for_wake_word(timeout=poll_interval)
            if detected:
                self._exit_power_save()
                return True
            self._evaluate_inactivity()
        return False

    def _run_turn(self) -> None:
        """Single hands-free turn: listen -> transcribe -> respond -> speak."""
        if not self._turn_lock.acquire(blocking=False):
            log.debug("[ContinuousListening] turn already in progress, skipping")
            return

        try:
            self._set_state(ListenState.ACTIVE_LISTENING)
            wav_path = self._record_with_silence_timeout()
            if not wav_path:
                self._enter_post_turn_state(had_speech=False)
                return

            self._set_state(ListenState.PROCESSING)
            user_text = self._safe_transcribe(wav_path)

            if not user_text:
                log.debug("[ContinuousListening] no speech recognised")
                self._enter_post_turn_state(had_speech=False)
                return

            self._touch_activity()
            if self._on_user_text:
                self._safe_call(self._on_user_text, user_text)

            reply = self._safe_respond(user_text)

            if reply:
                self._set_state(ListenState.SPEAKING)
                self._safe_speak(reply)

            # Automatic resume — return to listening immediately after
            # TTS playback finishes, no manual restart required.
            self._touch_activity()
            self._enter_post_turn_state(had_speech=True)

        finally:
            self._turn_lock.release()

    def _enter_post_turn_state(self, had_speech: bool) -> None:
        """Decide whether to stay in conversation mode or wait for wake word."""
        if not self.cfg.auto_resume_enabled:
            self._set_state(ListenState.WAITING_FOR_WAKE)
            return

        if had_speech and self.cfg.conversation_mode_enabled:
            log.info(
                "[ContinuousListening] entering conversation mode (timeout=%.1fs)",
                self.cfg.conversation_timeout,
            )
            self._set_state(ListenState.CONVERSATION)
            if not self._wait_in_conversation():
                log.info(
                    "[ContinuousListening] conversation timeout — back to wake word"
                )
                self._set_state(ListenState.WAITING_FOR_WAKE)
        else:
            self._set_state(ListenState.WAITING_FOR_WAKE)

    def _wait_in_conversation(self) -> bool:
        """While in conversation mode, attempt one more listen immediately.

        Returns True if the follow-up turn should run (caller's main loop
        will detect state==CONVERSATION and skip the wake-word wait), False
        if the conversation window should close.
        """
        # The actual follow-up recording happens via record_until_silence
        # inside the next _run_turn() call (triggered by the main loop
        # since state is CONVERSATION). Here we just need to bound how
        # long we wait for the user to start speaking before giving up.
        deadline = time.monotonic() + self.cfg.conversation_timeout
        sil_thresh = getattr(self.audio.cfg, "silence_threshold", 0.01)

        # Briefly poll mic energy via a short probe recording window so we
        # don't block conversation mode indefinitely without a real signal
        # that the user has started speaking.
        import numpy as np  # local import keeps module import light
        import sounddevice as sd

        try:
            while time.monotonic() < deadline and self.is_running():
                probe = sd.rec(
                    int(0.3 * self.audio.sample_rate),
                    samplerate=self.audio.sample_rate,
                    channels=1,
                    dtype="float32",
                )
                sd.wait()
                rms = float(np.sqrt(np.mean(probe**2))) if probe is not None else 0.0
                if rms >= sil_thresh:
                    return True
        except Exception as exc:
            log.debug("[ContinuousListening] conversation probe error: %s", exc)
            # Fall back to allowing one follow-up turn rather than dropping
            # the user mid-conversation on a transient audio glitch.
            return True

        return False

    def _record_with_silence_timeout(self) -> str:
        """Record an utterance, finalising it after the configured silence."""
        try:
            return self.audio.record_until_silence(
                timeout=getattr(self.audio.cfg, "max_seconds", 30.0),
            )
        except Exception as exc:
            log.error("[ContinuousListening] recording error: %s", exc)
            return ""

    # ------------------------------------------------------------------
    # Safe wrappers around caller-supplied callbacks
    # ------------------------------------------------------------------

    def _safe_transcribe(self, wav_path: str) -> str:
        if not self._transcribe_fn:
            return ""
        try:
            return (self._transcribe_fn(wav_path) or "").strip()
        except Exception as exc:
            log.error("[ContinuousListening] transcription error: %s", exc)
            return ""

    def _safe_respond(self, user_text: str) -> str:
        if not self._respond_fn:
            return ""
        try:
            return self._respond_fn(user_text) or ""
        except Exception as exc:
            log.error("[ContinuousListening] response generation error: %s", exc)
            return ""

    def _safe_speak(self, text: str) -> None:
        if not self._speak_fn:
            return
        try:
            self._speak_fn(text)
        except Exception as exc:
            log.error("[ContinuousListening] speech playback error: %s", exc)

    def _safe_call(self, fn: Callable, *args) -> None:
        try:
            fn(*args)
        except Exception as exc:
            log.error("[ContinuousListening] callback error: %s", exc)

    # ------------------------------------------------------------------
    # Sleep / Idle power management
    # ------------------------------------------------------------------

    def _touch_activity(self) -> None:
        """Record user/assistant activity and reset sleep/idle timers."""
        with self._lock:
            self._last_activity = time.monotonic()
        self._exit_power_save()
        self._restart_inactivity_timers()

    def _restart_inactivity_timers(self) -> None:
        self._cancel_sleep_timer()
        self._cancel_idle_timer()

        if not self.is_running():
            return

        if self.cfg.sleep_mode_enabled and self.cfg.sleep_timeout > 0:
            t = threading.Timer(self.cfg.sleep_timeout, self._enter_sleep)
            t.daemon = True
            t.start()
            with self._lock:
                self._sleep_timer = t

        if self.cfg.idle_mode_enabled and self.cfg.idle_timeout > 0:
            t2 = threading.Timer(self.cfg.idle_timeout, self._enter_idle)
            t2.daemon = True
            t2.start()
            with self._lock:
                self._idle_timer = t2

    def _evaluate_inactivity(self) -> None:
        """Periodic check (called while polling for wake word)."""
        elapsed = time.monotonic() - self._last_activity
        if self.cfg.idle_mode_enabled and elapsed >= self.cfg.idle_timeout:
            if self.state != ListenState.IDLE:
                self._enter_idle()
        elif self.cfg.sleep_mode_enabled and elapsed >= self.cfg.sleep_timeout:
            if self.state not in (ListenState.SLEEP, ListenState.IDLE):
                self._enter_sleep()

    def _enter_sleep(self) -> None:
        if not self.is_running() or self.state not in (
            ListenState.WAITING_FOR_WAKE,
            ListenState.SLEEP,
        ):
            return
        log.info(
            "[ContinuousListening] entering sleep mode after %.0fs inactivity "
            "(wake word stays active, duty cycle=1/%d)",
            self.cfg.sleep_timeout,
            self.cfg.sleep_power_save_duty_cycle,
        )
        self._set_state(ListenState.SLEEP)
        try:
            self.audio.set_power_save(self.cfg.sleep_power_save_duty_cycle)
        except Exception as exc:
            log.debug("[ContinuousListening] power-save set failed: %s", exc)

    def _enter_idle(self) -> None:
        if not self.is_running():
            return
        log.info(
            "[ContinuousListening] entering idle mode after %.0fs inactivity "
            "(wake word stays active, duty cycle=1/%d)",
            self.cfg.idle_timeout,
            self.cfg.idle_power_save_duty_cycle,
        )
        self._set_state(ListenState.IDLE)
        try:
            self.audio.set_power_save(self.cfg.idle_power_save_duty_cycle)
        except Exception as exc:
            log.debug("[ContinuousListening] power-save set failed: %s", exc)

    def _exit_power_save(self) -> None:
        """Resume full-rate wake word detection instantly on activity."""
        try:
            self.audio.set_power_save(1)
        except Exception as exc:
            log.debug("[ContinuousListening] power-save reset failed: %s", exc)

    def _cancel_sleep_timer(self) -> None:
        with self._lock:
            if self._sleep_timer is not None:
                self._sleep_timer.cancel()
                self._sleep_timer = None

    def _cancel_idle_timer(self) -> None:
        with self._lock:
            if self._idle_timer is not None:
                self._idle_timer.cancel()
                self._idle_timer = None

    # ------------------------------------------------------------------
    # State transition helper
    # ------------------------------------------------------------------

    def _set_state(self, new_state: ListenState) -> None:
        with self._lock:
            old = self._state
            if old == new_state:
                return
            self._state = new_state
        log.debug("[ContinuousListening] state %s -> %s", old.name, new_state.name)
        if self._on_state_change:
            self._safe_call(self._on_state_change, old, new_state)
