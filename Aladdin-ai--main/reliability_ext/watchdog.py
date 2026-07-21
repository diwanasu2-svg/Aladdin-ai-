"""
reliability_ext/watchdog.py — Phase 13 Feature 3
=================================================
Watchdog service — monitors components via heartbeat and auto-restarts
frozen or hung processes.

Features:
- Background watchdog thread
- Heartbeat-based liveness detection
- Configurable timeout per target (default 30s)
- Exponential backoff for restarts (1s, 2s, 4s, 8s, 16s — max 5 attempts)
- Prevents killing healthy processes
- Watchdog status reporting
- Alert callback on events
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

MAX_BACKOFF_SECONDS = 30.0
DEFAULT_TIMEOUT = 30


class WatchdogAlertLevel(str, Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


@dataclass
class WatchdogEvent:
    timestamp: float
    target_name: str
    event_type: str
    alert_level: WatchdogAlertLevel = WatchdogAlertLevel.INFO
    detail: str = ""


@dataclass
class WatchdogTarget:
    name: str
    heartbeat_fn: Optional[Callable[[], bool]] = None
    restart_fn: Optional[Callable[[], bool]] = None
    timeout_seconds: int = DEFAULT_TIMEOUT
    max_restarts: int = 5
    enabled: bool = True
    last_heartbeat: float = field(default_factory=time.time)
    restart_count: int = 0
    consecutive_failures: int = 0
    _backoff_index: int = 0

    BACKOFF_SCHEDULE = [1, 2, 4, 8, 16, 30]

    def record_beat(self) -> None:
        self.last_heartbeat = time.time()
        self.consecutive_failures = 0
        self._backoff_index = 0

    def is_timed_out(self, now: float) -> bool:
        return (now - self.last_heartbeat) > self.timeout_seconds

    def next_backoff(self) -> float:
        idx = min(self._backoff_index, len(self.BACKOFF_SCHEDULE) - 1)
        self._backoff_index += 1
        return float(self.BACKOFF_SCHEDULE[idx])


class Watchdog:
    """
    Monitors registered components and automatically restarts failed ones.

    Usage::

        wd = Watchdog(check_interval=10)
        wd.register(WatchdogTarget(
            name="ai_engine",
            heartbeat_fn=lambda: ai_engine.is_alive(),
            restart_fn=lambda: ai_engine.restart(),
            timeout_seconds=60,
        ))
        wd.start()

        # Services call periodically:
        wd.heartbeat("ai_engine")
    """

    def __init__(
        self,
        check_interval: int = 10,
        alert_callback: Optional[Callable[[WatchdogEvent], None]] = None,
    ) -> None:
        self._check_interval = check_interval
        self._alert_callback = alert_callback
        self._targets: Dict[str, WatchdogTarget] = {}
        self._events: List[WatchdogEvent] = []
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._restart_timers: Dict[str, threading.Timer] = {}

    # ── Registration ──────────────────────────────────────────────────────────

    def register(self, target: WatchdogTarget) -> None:
        with self._lock:
            self._targets[target.name] = target
        log.info("Watchdog: registered '%s' (timeout=%ds max_restarts=%d)",
                 target.name, target.timeout_seconds, target.max_restarts)

    def register_simple(
        self,
        name: str,
        *,
        heartbeat_fn: Optional[Callable] = None,
        restart_fn: Optional[Callable] = None,
        timeout_seconds: int = DEFAULT_TIMEOUT,
        max_restarts: int = 5,
    ) -> None:
        self.register(WatchdogTarget(
            name=name, heartbeat_fn=heartbeat_fn, restart_fn=restart_fn,
            timeout_seconds=timeout_seconds, max_restarts=max_restarts,
        ))

    def unregister(self, name: str) -> None:
        with self._lock:
            self._targets.pop(name, None)

    def enable(self, name: str) -> None:
        with self._lock:
            if name in self._targets:
                self._targets[name].enabled = True

    def disable(self, name: str) -> None:
        with self._lock:
            if name in self._targets:
                self._targets[name].enabled = False

    # ── Heartbeat API ─────────────────────────────────────────────────────────

    def heartbeat(self, name: str) -> None:
        """Services call this regularly to signal they are alive."""
        with self._lock:
            if name in self._targets:
                self._targets[name].record_beat()

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="Watchdog")
        self._thread.start()
        log.info("Watchdog: started (check_interval=%ds)", self._check_interval)

    def stop(self) -> None:
        self._running = False
        for timer in self._restart_timers.values():
            timer.cancel()
        if self._thread:
            self._thread.join(timeout=5)
        log.info("Watchdog: stopped")

    # ── Main loop ─────────────────────────────────────────────────────────────

    def _loop(self) -> None:
        while self._running:
            now = time.time()
            with self._lock:
                targets = list(self._targets.values())
            for target in targets:
                if target.enabled:
                    try:
                        self._check_target(target, now)
                    except Exception as e:
                        log.error("Watchdog: error checking '%s': %s", target.name, e)
            time.sleep(self._check_interval)

    def _check_target(self, target: WatchdogTarget, now: float) -> None:
        alive = True
        if target.heartbeat_fn:
            try:
                alive = bool(target.heartbeat_fn())
            except Exception:
                alive = False
        elif target.is_timed_out(now):
            alive = False

        if not alive:
            target.consecutive_failures += 1
            self._emit(WatchdogEvent(
                timestamp=now, target_name=target.name,
                event_type="timeout_detected", alert_level=WatchdogAlertLevel.CRITICAL,
                detail=f"consecutive_failures={target.consecutive_failures}",
            ))
            if target.restart_count >= target.max_restarts:
                self._emit(WatchdogEvent(
                    timestamp=now, target_name=target.name,
                    event_type="max_restarts_reached", alert_level=WatchdogAlertLevel.CRITICAL,
                    detail=f"giving up after {target.max_restarts} restarts",
                ))
                target.enabled = False
                return
            self._schedule_restart(target)
        else:
            if target.consecutive_failures > 0:
                log.info("Watchdog: '%s' is alive again", target.name)
                target.consecutive_failures = 0

    def _schedule_restart(self, target: WatchdogTarget) -> None:
        backoff = target.next_backoff()
        log.warning("Watchdog: '%s' unresponsive — restart in %.1fs (attempt %d/%d)",
                    target.name, backoff, target.restart_count + 1, target.max_restarts)
        timer = threading.Timer(backoff, self._do_restart, args=(target,))
        timer.daemon = True
        with self._lock:
            old = self._restart_timers.get(target.name)
            if old:
                old.cancel()
            self._restart_timers[target.name] = timer
        timer.start()

    def _do_restart(self, target: WatchdogTarget) -> None:
        target.restart_count += 1
        self._emit(WatchdogEvent(
            timestamp=time.time(), target_name=target.name,
            event_type="restart_triggered", alert_level=WatchdogAlertLevel.WARNING,
            detail=f"attempt={target.restart_count}",
        ))
        if not target.restart_fn:
            log.warning("Watchdog: no restart_fn for '%s'", target.name)
            return
        try:
            success = bool(target.restart_fn())
        except Exception as e:
            log.error("Watchdog: restart of '%s' raised: %s", target.name, e)
            success = False

        level = WatchdogAlertLevel.INFO if success else WatchdogAlertLevel.CRITICAL
        event_type = "restart_succeeded" if success else "restart_failed"
        if success:
            target.record_beat()
        self._emit(WatchdogEvent(timestamp=time.time(), target_name=target.name,
                                  event_type=event_type, alert_level=level))

    def _emit(self, event: WatchdogEvent) -> None:
        self._events.append(event)
        if len(self._events) > 1000:
            self._events = self._events[-1000:]
        _log = {
            WatchdogAlertLevel.INFO: log.info,
            WatchdogAlertLevel.WARNING: log.warning,
            WatchdogAlertLevel.CRITICAL: log.error,
        }.get(event.alert_level, log.info)
        _log("Watchdog [%s]: %s %s", event.target_name, event.event_type, event.detail)
        if self._alert_callback:
            try:
                self._alert_callback(event)
            except Exception as e:
                log.warning("Watchdog: alert callback error: %s", e)

    # ── Reporting ─────────────────────────────────────────────────────────────

    def get_events(self, limit: int = 50) -> List[WatchdogEvent]:
        return self._events[-limit:]

    def get_status(self) -> Dict[str, Any]:
        now = time.time()
        with self._lock:
            return {
                name: {
                    "enabled": t.enabled,
                    "restart_count": t.restart_count,
                    "consecutive_failures": t.consecutive_failures,
                    "last_heartbeat_ago_s": round(now - t.last_heartbeat, 1),
                    "timed_out": t.is_timed_out(now),
                }
                for name, t in self._targets.items()
            }
