"""
reliability_ext/reliability_manager.py — Phase 13 Feature 1
=============================================================
Centralised ReliabilityManager — integrates all reliability subsystems.

Features:
- Singleton pattern
- Component registration and health scoring (0–100)
- Failure detection and automatic recovery
- Event bus for status changes
- Integrates health monitor, watchdog, crash recovery, performance monitor
- Reliability dashboard data
- Thread-safe
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class ComponentStatus(str, Enum):
    HEALTHY    = "healthy"
    DEGRADED   = "degraded"
    UNHEALTHY  = "unhealthy"
    UNKNOWN    = "unknown"
    RECOVERING = "recovering"


@dataclass
class SystemHealth:
    overall: ComponentStatus
    score: float           # 0.0–1.0
    components: Dict[str, ComponentStatus]
    timestamp: float = field(default_factory=time.time)
    alerts: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "overall": self.overall.value,
            "score": round(self.score, 3),
            "score_100": round(self.score * 100, 1),
            "components": {k: v.value for k, v in self.components.items()},
            "timestamp": self.timestamp,
            "timestamp_iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.timestamp)),
            "alerts": self.alerts,
        }


@dataclass
class ComponentRecord:
    name: str
    status: ComponentStatus = ComponentStatus.UNKNOWN
    health_score: float = 1.0
    last_checked: float = 0.0
    failure_count: int = 0
    recovery_count: int = 0
    recovery_fn: Optional[Callable[[], bool]] = None
    description: str = ""

    def health_score_100(self) -> int:
        return int(self.health_score * 100)


class ReliabilityManager:
    """
    Central reliability management for Aladdin.

    Usage::

        rm = ReliabilityManager()
        rm.register_component("ai_engine", recovery_fn=restart_ai, description="Core LLM")
        rm.initialise_subsystems()
        rm.start()

        health = rm.get_system_health()
        log.info(health.to_dict())

    """

    MAX_AUTO_RECOVERY_ATTEMPTS: int = 3

    _instance: Optional["ReliabilityManager"] = None
    _lock = threading.Lock()

    def __new__(cls, *args, **kwargs):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super().__new__(cls)
                cls._instance._boot = False
        return cls._instance

    def __init__(self, check_interval: int = 30) -> None:
        if self._boot:
            return
        self._check_interval = check_interval
        self._components: Dict[str, ComponentRecord] = {}
        self._listeners: List[Callable[[str, ComponentStatus], None]] = []
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._comp_lock = threading.Lock()
        # Subsystem references
        self.health_monitor = None
        self.watchdog = None
        self.crash_recovery = None
        self.performance_monitor = None
        self.backup_system = None
        self.logging_system = None
        self._boot = True
        log.info("ReliabilityManager: singleton initialised (check_interval=%ds)", check_interval)

    def initialise_subsystems(
        self,
        *,
        enable_watchdog: bool = True,
        enable_crash_recovery: bool = True,
        enable_performance: bool = True,
        enable_backup: bool = False,
        enable_logging: bool = True,
        backup_dir: str = "backups",
        log_dir: str = "logs",
    ) -> None:
        """Wire all reliability subsystems together."""
        from .health_monitor import HealthMonitor
        from .crash_recovery import CrashRecovery
        from .performance_monitor import PerformanceMonitor
        from .logging_system import LoggingSystem

        if enable_logging:
            self.logging_system = LoggingSystem(log_dir=log_dir)
            self.logging_system.configure()

        self.health_monitor = HealthMonitor(check_interval=self._check_interval)
        self.health_monitor.on_status_change(self._on_health_change)

        if enable_watchdog:
            from .watchdog import Watchdog
            self.watchdog = Watchdog()

        if enable_crash_recovery:
            self.crash_recovery = CrashRecovery(state_dir=backup_dir)
            self.crash_recovery.install_excepthook()

        if enable_performance:
            self.performance_monitor = PerformanceMonitor()

        if enable_backup:
            from .backup_system import BackupSystem
            self.backup_system = BackupSystem(backup_dir=backup_dir)

        log.info("ReliabilityManager: subsystems initialised")

    # ── Component registration ────────────────────────────────────────────────

    def register_component(
        self,
        name: str,
        *,
        recovery_fn: Optional[Callable[[], bool]] = None,
        description: str = "",
    ) -> None:
        with self._comp_lock:
            self._components[name] = ComponentRecord(
                name=name, recovery_fn=recovery_fn, description=description
            )
        log.info("ReliabilityManager: registered component '%s'", name)

    def update_component_status(
        self,
        name: str,
        status: ComponentStatus,
        score: float = 1.0,
        _from_recovery: bool = False,
    ) -> None:
        with self._comp_lock:
            if name not in self._components:
                self._components[name] = ComponentRecord(name=name)
            comp = self._components[name]
            old_status = comp.status
            comp.status = status
            comp.health_score = max(0.0, min(1.0, score))
            comp.last_checked = time.time()
            if status == ComponentStatus.UNHEALTHY:
                comp.failure_count += 1

        if status != old_status:
            for listener in self._listeners:
                try:
                    listener(name, status)
                except Exception as e:
                    log.warning("ReliabilityManager: listener error: %s", e)

        if status == ComponentStatus.UNHEALTHY and not _from_recovery:
            with self._comp_lock:
                comp = self._components.get(name)
                already = comp and comp.status == ComponentStatus.RECOVERING
            if not already:
                self._trigger_recovery(name)

    def _on_health_change(self, name: str, healthy: bool) -> None:
        status = ComponentStatus.HEALTHY if healthy else ComponentStatus.UNHEALTHY
        self.update_component_status(name, status, 1.0 if healthy else 0.0)

    def _trigger_recovery(self, name: str) -> None:
        t = threading.Thread(target=self._do_recovery, args=(name,), daemon=True, name=f"Recovery-{name}")
        t.start()

    def _do_recovery(self, name: str) -> None:
        with self._comp_lock:
            comp = self._components.get(name)
        if not comp or not comp.recovery_fn:
            return

        if comp.failure_count > self.MAX_AUTO_RECOVERY_ATTEMPTS:
            log.error("ReliabilityManager: '%s' exceeded max recovery attempts — manual intervention required", name)
            self.update_component_status(name, ComponentStatus.UNHEALTHY, 0.0, _from_recovery=True)
            return

        log.info("ReliabilityManager: recovering '%s' (failure #%d)", name, comp.failure_count)
        self.update_component_status(name, ComponentStatus.RECOVERING, 0.5, _from_recovery=True)
        try:
            success = bool(comp.recovery_fn())
        except Exception as e:
            log.error("ReliabilityManager: recovery exception for '%s': %s", name, e)
            success = False

        if success:
            comp.recovery_count += 1
            self.update_component_status(name, ComponentStatus.HEALTHY, 1.0, _from_recovery=True)
            log.info("ReliabilityManager: '%s' recovered", name)
        else:
            self.update_component_status(name, ComponentStatus.UNHEALTHY, 0.0, _from_recovery=True)
            log.error("ReliabilityManager: recovery of '%s' failed", name)

    # ── Health ────────────────────────────────────────────────────────────────

    def get_system_health(self) -> SystemHealth:
        with self._comp_lock:
            comps = dict(self._components)

        if not comps:
            return SystemHealth(ComponentStatus.UNKNOWN, 1.0, {})

        scores = [c.health_score for c in comps.values()]
        avg = sum(scores) / len(scores)

        if avg >= 0.8:
            overall = ComponentStatus.HEALTHY
        elif avg >= 0.5:
            overall = ComponentStatus.DEGRADED
        else:
            overall = ComponentStatus.UNHEALTHY

        alerts = [
            f"'{n}' is {s.value}" for n, s in
            {n: c.status for n, c in comps.items()}.items()
            if s in (ComponentStatus.UNHEALTHY, ComponentStatus.DEGRADED)
        ]
        return SystemHealth(
            overall=overall,
            score=avg,
            components={n: c.status for n, c in comps.items()},
            alerts=alerts,
        )

    def get_dashboard(self) -> dict:
        """Full dashboard data for monitoring UI."""
        health = self.get_system_health()
        with self._comp_lock:
            comps = {
                n: {
                    "status": c.status.value,
                    "health_score_100": c.health_score_100(),
                    "failure_count": c.failure_count,
                    "recovery_count": c.recovery_count,
                    "last_checked": c.last_checked,
                    "description": c.description,
                }
                for n, c in self._components.items()
            }
        perf = {}
        if self.performance_monitor:
            perf = self.performance_monitor.get_summary()

        return {
            "system_health": health.to_dict(),
            "components": comps,
            "performance": perf,
            "watchdog": self.watchdog.get_status() if self.watchdog else {},
            "timestamp": time.time(),
        }

    # ── Events ────────────────────────────────────────────────────────────────

    def on_status_change(self, callback: Callable[[str, ComponentStatus], None]) -> None:
        self._listeners.append(callback)

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        if self.health_monitor:
            self.health_monitor.start()
        if self.watchdog:
            self.watchdog.start()
        if self.performance_monitor:
            self.performance_monitor.start()
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True, name="ReliabilityManager")
        self._thread.start()
        log.info("ReliabilityManager: started")

    def stop(self) -> None:
        self._running = False
        if self.health_monitor:
            self.health_monitor.stop()
        if self.watchdog:
            self.watchdog.stop()
        if self.performance_monitor:
            self.performance_monitor.stop()
        if self._thread:
            self._thread.join(timeout=5)
        log.info("ReliabilityManager: stopped")

    def _monitor_loop(self) -> None:
        while self._running:
            h = self.get_system_health()
            if h.overall == ComponentStatus.UNHEALTHY:
                log.error("SYSTEM CRITICAL: score=%.2f alerts=%s", h.score, h.alerts)
            elif h.overall == ComponentStatus.DEGRADED:
                log.warning("SYSTEM DEGRADED: score=%.2f", h.score)
            time.sleep(self._check_interval)
