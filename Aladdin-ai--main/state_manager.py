"""Automatic state management: sleep mode, resume listening, etc."""

from __future__ import annotations

import logging
import threading
import time
from enum import Enum
from typing import Callable, Optional

log = logging.getLogger(__name__)


class AudioState(Enum):
    """Audio subsystem states."""

    IDLE = "idle"  # Not listening
    LISTENING = "listening"  # Listening, waiting for wake word or input
    PROCESSING = "processing"  # Processing audio (recording/transcription)
    SPEAKING = "speaking"  # TTS playback
    SLEEPING = "sleeping"  # Low-power sleep mode


class StateManager:
    """Manages audio system state transitions and auto-resume."""

    def __init__(
        self,
        auto_sleep_enabled: bool = True,
        auto_sleep_timeout_ms: int = 3000,
        auto_resume_enabled: bool = True,
        auto_resume_delay_ms: int = 100,
    ):
        """Initialize state manager.

        Args:
            auto_sleep_enabled: Enable automatic sleep mode
            auto_sleep_timeout_ms: Timeout before sleeping (ms)
            auto_resume_enabled: Enable automatic resume listening
            auto_resume_delay_ms: Delay before resuming (ms)
        """
        self.auto_sleep_enabled = auto_sleep_enabled
        self.auto_sleep_timeout_ms = auto_sleep_timeout_ms
        self.auto_resume_enabled = auto_resume_enabled
        self.auto_resume_delay_ms = auto_resume_delay_ms

        self._current_state = AudioState.IDLE
        self._last_activity_time = time.time()
        self._state_lock = threading.RLock()
        self._sleep_timer = None
        self._resume_timer = None

        # Callbacks
        self._on_state_changed: Optional[Callable] = None
        self._on_should_sleep: Optional[Callable] = None
        self._on_should_resume: Optional[Callable] = None

    def set_state(self, new_state: AudioState) -> None:
        """Change audio state.

        Args:
            new_state: New state
        """
        with self._state_lock:
            if self._current_state == new_state:
                return

            old_state = self._current_state
            self._current_state = new_state
            self._last_activity_time = time.time()

            log.debug(f"State transition: {old_state.value} -> {new_state.value}")

            # Cancel any pending timers
            if self._sleep_timer:
                self._sleep_timer.cancel()
                self._sleep_timer = None
            if self._resume_timer:
                self._resume_timer.cancel()
                self._resume_timer = None

            # Handle state-specific logic
            if new_state == AudioState.LISTENING:
                # Start sleep timer
                if self.auto_sleep_enabled:
                    self._schedule_sleep()

            elif new_state == AudioState.PROCESSING:
                # Cancel sleep timer during processing
                pass

            elif new_state == AudioState.SPEAKING:
                # Processing complete, will auto-resume after playback
                pass

            elif new_state == AudioState.SLEEPING:
                # In sleep mode, reduce CPU usage
                log.info("Entering sleep mode")

            # Emit callback
            if self._on_state_changed:
                try:
                    self._on_state_changed(old_state, new_state)
                except Exception as e:
                    log.error(f"State change callback error: {e}")

    def get_state(self) -> AudioState:
        """Get current state."""
        with self._state_lock:
            return self._current_state

    def resume_listening(self) -> None:
        """Resume listening with optional delay."""
        if not self.auto_resume_enabled:
            self.set_state(AudioState.LISTENING)
            return

        def resume():
            time.sleep(self.auto_resume_delay_ms / 1000.0)
            self.set_state(AudioState.LISTENING)
            if self._on_should_resume:
                try:
                    self._on_should_resume()
                except Exception as e:
                    log.error(f"Resume callback error: {e}")

        self._resume_timer = threading.Timer(self.auto_resume_delay_ms / 1000.0, resume)
        self._resume_timer.daemon = True
        self._resume_timer.start()

    def _schedule_sleep(self) -> None:
        """Schedule automatic sleep."""

        def sleep_timeout():
            with self._state_lock:
                if self._current_state == AudioState.LISTENING:
                    self.set_state(AudioState.SLEEPING)
                    if self._on_should_sleep:
                        try:
                            self._on_should_sleep()
                        except Exception as e:
                            log.error(f"Sleep callback error: {e}")

        self._sleep_timer = threading.Timer(
            self.auto_sleep_timeout_ms / 1000.0, sleep_timeout
        )
        self._sleep_timer.daemon = True
        self._sleep_timer.start()

    def set_on_state_changed(self, callback: Optional[Callable]) -> None:
        """Set state change callback.

        Args:
            callback: Function(old_state, new_state)
        """
        self._on_state_changed = callback

    def set_on_should_sleep(self, callback: Optional[Callable]) -> None:
        """Set sleep callback.

        Args:
            callback: Function() called when entering sleep
        """
        self._on_should_sleep = callback

    def set_on_should_resume(self, callback: Optional[Callable]) -> None:
        """Set resume callback.

        Args:
            callback: Function() called when resuming
        """
        self._on_should_resume = callback

    def shutdown(self) -> None:
        """Cleanup."""
        with self._state_lock:
            if self._sleep_timer:
                self._sleep_timer.cancel()
            if self._resume_timer:
                self._resume_timer.cancel()
