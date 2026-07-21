"""
reliability_ext/auto_restart.py — Phase 13 Feature 6
=====================================================
Automatic service restart with exponential backoff and retry limits.

Features:
- Restart if AI service, voice pipeline, or background service fails
- Max 5 attempts with exponential backoff (1,2,4,8,16s)
- State persistence across restarts
- Automatic service recovery with configurable strategies
- Circuit breaker: stops retrying after max attempts
- Thread-safe; multiple services managed independently
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

BACKOFF_SCHEDULE = [1, 2, 4, 8, 16]  # seconds
DEFAULT_MAX_ATTEMPTS = 5


class RestartState(str, Enum):
    IDLE       = "idle"
    RUNNING    = "running"
    RESTARTING = "restarting"
    FAILED     = "failed"   # exceeded max attempts
    SUSPENDED  = "suspended"


@dataclass
class ServiceRecord:
    name: str
    start_fn: Callable[[], bool]
    stop_fn: Optional[Callable[[], None]] = None
    health_fn: Optional[Callable[[], bool]] = None
    max_attempts: int = DEFAULT_MAX_ATTEMPTS
    state: RestartState = RestartState.IDLE
    attempt_count: int = 0
    backoff_index: int = 0
    last_started: float = 0.0
    last_failure: float = 0.0
    total_restarts: int = 0
    last_error: str = ""

    def next_backoff(self) -> float:
        idx = min(self.backoff_index, len(BACKOFF_SCHEDULE) - 1)
        self.backoff_index += 1
        return float(BACKOFF_SCHEDULE[idx])

    def reset_backoff(self) -> None:
        self.backoff_index = 0
        self.attempt_count = 0

    def is_circuit_open(self) -> bool:
        return self.state == RestartState.FAILED


class AutoRestart:
    """
    Manages automatic restart of failed services.

    Usage::

        ar = AutoRestart()
        ar.register("ai_engine", start_fn=ai_engine.start, health_fn=ai_engine.is_healthy)
        ar.start_monitoring()

        # When a service fails:
        ar.trigger_restart("ai_engine", reason="heartbeat timeout")
    """

    def __init__(self, check_interval: int = 15) -> None:
        self._check_interval = check_interval
        self._services: Dict[str, ServiceRecord] = {}
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._restart_history: List[dict] = []
        self._restart_callbacks: List[Callable] = []

    # ── Registration ──────────────────────────────────────────────────────────

    def register(
        self,
        name: str,
        start_fn: Callable[[], bool],
        *,
        stop_fn: Optional[Callable[[], None]] = None,
        health_fn: Optional[Callable[[], bool]] = None,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
    ) -> None:
        with self._lock:
            self._services[name] = ServiceRecord(
                name=name, start_fn=start_fn, stop_fn=stop_fn,
                health_fn=health_fn, max_attempts=max_attempts,
            )
        log.info("AutoRestart: registered '%s' (max_attempts=%d)", name, max_attempts)

    def on_restart(self, callback: Callable) -> None:
        """Register a callback invoked on every restart event."""
        self._restart_callbacks.append(callback)

    # ── Trigger ───────────────────────────────────────────────────────────────

    def trigger_restart(self, name: str, reason: str = "") -> None:
        """Manually trigger a restart for a service."""
        with self._lock:
            service = self._services.get(name)
        if not service:
            log.error("AutoRestart: unknown service '%s'", name)
            return
        if service.is_circuit_open():
            log.error("AutoRestart: '%s' circuit open — max attempts reached, manual intervention needed", name)
            return
        # Run restart in background thread to not block caller
        t = threading.Thread(target=self._do_restart, args=(service, reason), daemon=True,
                             name=f"Restart-{name}")
        t.start()

    def _do_restart(self, service: ServiceRecord, reason: str) -> None:
        if service.attempt_count >= service.max_attempts:
            service.state = RestartState.FAILED
            log.error("AutoRestart: '%s' exceeded max attempts (%d). CIRCUIT OPEN.", service.name, service.max_attempts)
            self._record_event(service.name, "circuit_open", reason, False)
            return

        backoff = service.next_backoff()
        service.attempt_count += 1
        service.state = RestartState.RESTARTING
        service.last_failure = time.time()

        log.warning("AutoRestart: restarting '%s' in %.1fs (attempt %d/%d) reason=%s",
                    service.name, backoff, service.attempt_count, service.max_attempts, reason)

        time.sleep(backoff)

        # Stop cleanly if possible
        if service.stop_fn:
            try:
                service.stop_fn()
            except Exception as e:
                log.warning("AutoRestart: stop_fn for '%s' raised: %s", service.name, e)

        # Attempt start
        try:
            success = bool(service.start_fn())
        except Exception as e:
            success = False
            service.last_error = str(e)
            log.error("AutoRestart: start_fn for '%s' raised: %s", service.name, e)

        if success:
            service.state = RestartState.RUNNING
            service.last_started = time.time()
            service.total_restarts += 1
            service.reset_backoff()
            log.info("AutoRestart: '%s' restarted successfully (total=%d)", service.name, service.total_restarts)
            self._record_event(service.name, "restart_success", reason, True)
            for cb in self._restart_callbacks:
                try:
                    cb(service.name, True)
                except Exception as e:
                    log.warning("AutoRestart: callback error: %s", e)
        else:
            service.state = RestartState.IDLE  # Will retry on next check
            log.error("AutoRestart: '%s' restart failed (attempt %d/%d)",
                      service.name, service.attempt_count, service.max_attempts)
            self._record_event(service.name, "restart_failed", reason, False)
            for cb in self._restart_callbacks:
                try:
                    cb(service.name, False)
                except Exception as e:
                    log.warning("AutoRestart: callback error: %s", e)

    def _record_event(self, name: str, event: str, reason: str, success: bool) -> None:
        self._restart_history.append({
            "service": name, "event": event, "reason": reason,
            "success": success, "timestamp": time.time(),
        })
        if len(self._restart_history) > 1000:
            self._restart_history = self._restart_history[-1000:]

    # ── Monitoring loop ───────────────────────────────────────────────────────

    def start_monitoring(self) -> None:
        """Start background health-check loop."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True, name="AutoRestart-Monitor")
        self._thread.start()
        log.info("AutoRestart: monitoring started (check_interval=%ds)", self._check_interval)

    def stop_monitoring(self) -> None:
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)

    def _monitor_loop(self) -> None:
        while self._running:
            with self._lock:
                services = list(self._services.values())
            for svc in services:
                if svc.state == RestartState.FAILED or svc.state == RestartState.RESTARTING:
                    continue
                if svc.health_fn:
                    try:
                        healthy = bool(svc.health_fn())
                    except Exception:
                        healthy = False
                    if not healthy:
                        self.trigger_restart(svc.name, reason="health_fn returned False")
            time.sleep(self._check_interval)

    # ── Status ────────────────────────────────────────────────────────────────

    def get_status(self) -> Dict[str, Any]:
        with self._lock:
            return {
                name: {
                    "state": svc.state.value,
                    "attempt_count": svc.attempt_count,
                    "total_restarts": svc.total_restarts,
                    "last_error": svc.last_error,
                    "circuit_open": svc.is_circuit_open(),
                }
                for name, svc in self._services.items()
            }

    def reset_circuit(self, name: str) -> bool:
        """Manually reset the circuit breaker for a service (after fixing the root cause)."""
        with self._lock:
            svc = self._services.get(name)
        if not svc:
            return False
        svc.reset_backoff()
        svc.state = RestartState.IDLE
        log.info("AutoRestart: circuit reset for '%s'", name)
        return True

    def get_history(self, limit: int = 50) -> List[dict]:
        return self._restart_history[-limit:]
