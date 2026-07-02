"""production/battery_manager.py — Phase 15, Feature 5: Battery Optimization.

Optimizes background services, wake locks, resource release, and adapts
behavior based on battery level for Android and desktop.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class BatteryLevel(str, Enum):
    CRITICAL = "critical"    # < 10%
    LOW      = "low"         # 10–20%
    MEDIUM   = "medium"      # 20–50%
    HIGH     = "high"        # 50–80%
    FULL     = "full"        # 80–100%


class PowerMode(str, Enum):
    PERFORMANCE = "performance"   # No limits
    BALANCED    = "balanced"      # Default
    SAVER       = "saver"         # Background tasks paused
    ULTRA_SAVER = "ultra_saver"   # Only critical ops


@dataclass
class BatteryState:
    level_pct: float = 100.0
    is_charging: bool = True
    temperature_celsius: float = 30.0
    voltage_mv: int = 4000
    is_power_save_mode: bool = False

    @property
    def level(self) -> BatteryLevel:
        if self.level_pct < 10:
            return BatteryLevel.CRITICAL
        elif self.level_pct < 20:
            return BatteryLevel.LOW
        elif self.level_pct < 50:
            return BatteryLevel.MEDIUM
        elif self.level_pct < 80:
            return BatteryLevel.HIGH
        return BatteryLevel.FULL

    @property
    def recommended_mode(self) -> PowerMode:
        if self.is_charging:
            return PowerMode.PERFORMANCE
        if self.is_power_save_mode or self.level_pct < 10:
            return PowerMode.ULTRA_SAVER
        if self.level_pct < 20:
            return PowerMode.SAVER
        if self.level_pct < 50:
            return PowerMode.BALANCED
        return PowerMode.PERFORMANCE


class WakeLockManager:
    """Lightweight wake lock tracker to avoid holding CPU wake unnecessarily."""

    def __init__(self) -> None:
        self._locks: Dict[str, float] = {}   # tag → acquired_at
        self._lock = threading.Lock()

    def acquire(self, tag: str, timeout_seconds: float = 60.0) -> None:
        with self._lock:
            self._locks[tag] = time.time() + timeout_seconds
        log.debug("[WakeLock] Acquired: %s (timeout %.0fs)", tag, timeout_seconds)

    def release(self, tag: str) -> None:
        with self._lock:
            self._locks.pop(tag, None)
        log.debug("[WakeLock] Released: %s", tag)

    def release_all(self) -> None:
        with self._lock:
            released = list(self._locks.keys())
            self._locks.clear()
        if released:
            log.info("[WakeLock] Released all: %s", released)

    def cleanup_expired(self) -> int:
        now = time.time()
        with self._lock:
            expired = [t for t, exp in self._locks.items() if exp < now]
            for t in expired:
                del self._locks[t]
        return len(expired)

    @property
    def active_locks(self) -> List[str]:
        now = time.time()
        with self._lock:
            return [t for t, exp in self._locks.items() if exp >= now]


class BatteryManager:
    """Central battery optimization manager for Aladdin."""

    # Polling intervals per mode (seconds)
    _POLL_INTERVAL: Dict[PowerMode, float] = {
        PowerMode.PERFORMANCE: 30.0,
        PowerMode.BALANCED:    30.0,
        PowerMode.SAVER:       60.0,
        PowerMode.ULTRA_SAVER: 120.0,
    }

    # Network call throttle per mode
    _NETWORK_THROTTLE: Dict[PowerMode, float] = {
        PowerMode.PERFORMANCE: 0.0,
        PowerMode.BALANCED:    0.5,
        PowerMode.SAVER:       2.0,
        PowerMode.ULTRA_SAVER: 10.0,
    }

    def __init__(self, check_interval: float = 30.0) -> None:
        self.wake_locks = WakeLockManager()
        self._state = BatteryState()
        self._mode = PowerMode.BALANCED
        self._check_interval = check_interval
        self._callbacks: List[Callable[[BatteryState, PowerMode], None]] = []
        self._monitor_thread: Optional[threading.Thread] = None
        self._running = False
        self._is_android = os.path.exists("/system/build.prop")

    # ------------------------------------------------------------------
    # State reading
    # ------------------------------------------------------------------

    def _read_linux_battery(self) -> Optional[BatteryState]:
        """Read battery from Linux /sys filesystem."""
        power_supply_dir = "/sys/class/power_supply"
        if not os.path.exists(power_supply_dir):
            return None
        try:
            for entry in os.listdir(power_supply_dir):
                bat_path = os.path.join(power_supply_dir, entry)
                cap_file = os.path.join(bat_path, "capacity")
                status_file = os.path.join(bat_path, "status")
                if os.path.exists(cap_file):
                    capacity = float(open(cap_file).read().strip())
                    status = open(status_file).read().strip() if os.path.exists(status_file) else "Unknown"
                    charging = status in ("Charging", "Full")
                    return BatteryState(level_pct=capacity, is_charging=charging)
        except Exception as exc:
            log.debug("[Battery] Linux read error: %s", exc)
        return None

    def _read_android_battery(self) -> Optional[BatteryState]:
        """Read battery via Android dumpsys."""
        try:
            result = os.popen("dumpsys battery 2>/dev/null").read()
            level = 100.0
            charging = True
            for line in result.splitlines():
                line = line.strip()
                if line.startswith("level:"):
                    level = float(line.split(":")[1].strip())
                elif line.startswith("status:"):
                    # 2 = charging, 3 = discharging
                    status_code = int(line.split(":")[1].strip())
                    charging = status_code == 2
            return BatteryState(level_pct=level, is_charging=charging)
        except Exception as exc:
            log.debug("[Battery] Android read error: %s", exc)
        return None

    def refresh_state(self) -> BatteryState:
        """Read current battery state from OS."""
        state = None
        if self._is_android:
            state = self._read_android_battery()
        if state is None:
            state = self._read_linux_battery()
        if state is None:
            # Desktop / unknown — assume plugged in
            state = BatteryState(level_pct=100.0, is_charging=True)

        self._state = state
        new_mode = state.recommended_mode
        if new_mode != self._mode:
            log.info("[Battery] Mode change: %s → %s (%.0f%%)",
                     self._mode.value, new_mode.value, state.level_pct)
            self._mode = new_mode
            for cb in self._callbacks:
                try:
                    cb(state, new_mode)
                except Exception as exc:
                    log.warning("[Battery] Callback error: %s", exc)

        return self._state

    # ------------------------------------------------------------------
    # Monitoring
    # ------------------------------------------------------------------

    def start_monitoring(self) -> None:
        if self._monitor_thread and self._monitor_thread.is_alive():
            return
        self._running = True

        def _loop() -> None:
            while self._running:
                self.refresh_state()
                self.wake_locks.cleanup_expired()
                time.sleep(self._check_interval)

        self._monitor_thread = threading.Thread(target=_loop, daemon=True, name="BatteryMonitor")
        self._monitor_thread.start()
        log.info("[Battery] Monitoring started")

    def stop_monitoring(self) -> None:
        self._running = False

    # ------------------------------------------------------------------
    # Adaptive behavior helpers
    # ------------------------------------------------------------------

    def register_callback(self, fn: Callable[[BatteryState, PowerMode], None]) -> None:
        """Called whenever the power mode changes."""
        self._callbacks.append(fn)

    def should_run_background_task(self) -> bool:
        return self._mode not in (PowerMode.SAVER, PowerMode.ULTRA_SAVER)

    def network_throttle_seconds(self) -> float:
        return self._NETWORK_THROTTLE[self._mode]

    def ai_inference_allowed(self) -> bool:
        return self._mode != PowerMode.ULTRA_SAVER

    def voice_pipeline_idle_release(self) -> None:
        """Release all voice-related wake locks when pipeline is idle."""
        voice_locks = [t for t in self.wake_locks.active_locks if "voice" in t.lower()]
        for tag in voice_locks:
            self.wake_locks.release(tag)
        log.info("[Battery] Released %d voice wake locks", len(voice_locks))

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def state(self) -> BatteryState:
        return self._state

    @property
    def mode(self) -> PowerMode:
        return self._mode

    def summary(self) -> Dict[str, Any]:
        return {
            "level_pct": self._state.level_pct,
            "is_charging": self._state.is_charging,
            "battery_level": self._state.level.value,
            "power_mode": self._mode.value,
            "active_wake_locks": self.wake_locks.active_locks,
            "background_tasks_allowed": self.should_run_background_task(),
            "ai_inference_allowed": self.ai_inference_allowed(),
        }
