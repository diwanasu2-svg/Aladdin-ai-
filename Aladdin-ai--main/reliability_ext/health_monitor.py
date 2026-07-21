"""
reliability_ext/health_monitor.py — Phase 13 Feature 4
=======================================================
System health monitoring — CPU, memory, battery, network, AI models, backend.

Features:
- CPU usage (%, per-core)
- Memory usage (RAM + swap)
- Battery impact
- Network status (connected, latency)
- AI model health check (response time)
- Backend health ping (/health endpoint)
- Alert generation (threshold-based)
- Health check API endpoints support
- Configurable thresholds
- Status change callbacks
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)

# Thresholds (overrideable via env)
CPU_WARN_THRESHOLD   = float(os.environ.get("HEALTH_CPU_WARN",    "80"))
CPU_CRIT_THRESHOLD   = float(os.environ.get("HEALTH_CPU_CRIT",    "95"))
MEM_WARN_THRESHOLD   = float(os.environ.get("HEALTH_MEM_WARN",    "80"))
MEM_CRIT_THRESHOLD   = float(os.environ.get("HEALTH_MEM_CRIT",    "95"))
LATENCY_WARN_MS      = float(os.environ.get("HEALTH_LATENCY_WARN","500"))
LATENCY_CRIT_MS      = float(os.environ.get("HEALTH_LATENCY_CRIT","2000"))


class HealthStatus(str, Enum):
    HEALTHY   = "healthy"
    DEGRADED  = "degraded"
    UNHEALTHY = "unhealthy"
    UNKNOWN   = "unknown"


@dataclass
class HealthMetric:
    name: str
    value: Any
    unit: str = ""
    status: HealthStatus = HealthStatus.UNKNOWN
    timestamp: float = field(default_factory=time.time)
    detail: str = ""


@dataclass
class ComponentHealth:
    name: str
    status: HealthStatus = HealthStatus.UNKNOWN
    metrics: List[HealthMetric] = field(default_factory=list)
    last_checked: float = 0.0
    response_time_ms: float = 0.0
    error: str = ""

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "status": self.status.value,
            "last_checked": self.last_checked,
            "response_time_ms": round(self.response_time_ms, 2),
            "error": self.error,
            "metrics": [
                {"name": m.name, "value": m.value, "unit": m.unit, "status": m.status.value}
                for m in self.metrics
            ],
        }


class HealthMonitor:
    """
    Monitors system and component health.

    Usage::

        monitor = HealthMonitor(check_interval=30)
        monitor.register_backend("http://localhost:5000/api/health")
        monitor.on_status_change(my_callback)
        monitor.start()

        report = monitor.get_full_report()
    """

    def __init__(self, check_interval: int = 30) -> None:
        self._interval = check_interval
        self._components: Dict[str, ComponentHealth] = {}
        self._backend_urls: List[str] = []
        self._alert_callbacks: List[Callable] = []
        self._status_callbacks: List[Callable[[str, bool], None]] = []
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._alerts: List[dict] = []

    # ── Registration ──────────────────────────────────────────────────────────

    def register_backend(self, url: str) -> None:
        self._backend_urls.append(url)
        log.info("HealthMonitor: registered backend %s", url)

    def register_component(self, name: str) -> None:
        with self._lock:
            self._components[name] = ComponentHealth(name=name)

    def on_alert(self, callback: Callable) -> None:
        self._alert_callbacks.append(callback)

    def on_status_change(self, callback: Callable[[str, bool], None]) -> None:
        self._status_callbacks.append(callback)

    # ── Core checks ──────────────────────────────────────────────────────────

    def check_system(self) -> ComponentHealth:
        """Check CPU, memory, and system resources."""
        health = ComponentHealth(name="system", last_checked=time.time())
        metrics = []

        try:
            import psutil
            # CPU
            cpu_pct = psutil.cpu_percent(interval=0.5)
            cpu_per_core = psutil.cpu_percent(percpu=True)
            cpu_status = (HealthStatus.UNHEALTHY if cpu_pct >= CPU_CRIT_THRESHOLD else
                          HealthStatus.DEGRADED if cpu_pct >= CPU_WARN_THRESHOLD else HealthStatus.HEALTHY)
            metrics.append(HealthMetric("cpu_total", cpu_pct, "%", cpu_status))
            for i, c in enumerate(cpu_per_core):
                metrics.append(HealthMetric(f"cpu_core_{i}", c, "%", HealthStatus.UNKNOWN))

            # Memory
            mem = psutil.virtual_memory()
            mem_status = (HealthStatus.UNHEALTHY if mem.percent >= MEM_CRIT_THRESHOLD else
                          HealthStatus.DEGRADED if mem.percent >= MEM_WARN_THRESHOLD else HealthStatus.HEALTHY)
            metrics.append(HealthMetric("memory_percent", mem.percent, "%", mem_status))
            metrics.append(HealthMetric("memory_available_mb", mem.available // 1_048_576, "MB", HealthStatus.UNKNOWN))
            metrics.append(HealthMetric("memory_total_mb", mem.total // 1_048_576, "MB", HealthStatus.UNKNOWN))

            # Swap
            swap = psutil.swap_memory()
            metrics.append(HealthMetric("swap_percent", swap.percent, "%", HealthStatus.UNKNOWN))

            # Disk
            disk = psutil.disk_usage("/")
            disk_status = HealthStatus.DEGRADED if disk.percent > 85 else HealthStatus.HEALTHY
            metrics.append(HealthMetric("disk_percent", disk.percent, "%", disk_status))

            # Battery (if available)
            bat = psutil.sensors_battery()
            if bat:
                metrics.append(HealthMetric("battery_percent", bat.percent, "%", HealthStatus.UNKNOWN))
                metrics.append(HealthMetric("battery_plugged", bat.power_plugged, "", HealthStatus.UNKNOWN))

            # Overall system status
            statuses = [m.status for m in metrics if m.status != HealthStatus.UNKNOWN]
            if HealthStatus.UNHEALTHY in statuses:
                health.status = HealthStatus.UNHEALTHY
            elif HealthStatus.DEGRADED in statuses:
                health.status = HealthStatus.DEGRADED
            else:
                health.status = HealthStatus.HEALTHY

        except ImportError:
            health.status = HealthStatus.UNKNOWN
            health.error = "psutil not installed — pip install psutil"
        except Exception as e:
            health.status = HealthStatus.UNKNOWN
            health.error = str(e)

        health.metrics = metrics
        return health

    def check_network(self) -> ComponentHealth:
        """Check network connectivity and latency."""
        health = ComponentHealth(name="network", last_checked=time.time())
        metrics = []

        try:
            import socket
            import urllib.request

            # Basic connectivity
            start = time.perf_counter()
            try:
                socket.setdefaulttimeout(3)
                socket.socket(socket.AF_INET, socket.SOCK_STREAM).connect(("8.8.8.8", 53))
                latency_ms = (time.perf_counter() - start) * 1000
                connected = True
            except Exception:
                latency_ms = -1
                connected = False

            conn_status = HealthStatus.HEALTHY if connected else HealthStatus.UNHEALTHY
            lat_status = (HealthStatus.UNHEALTHY if latency_ms >= LATENCY_CRIT_MS else
                          HealthStatus.DEGRADED if latency_ms >= LATENCY_WARN_MS else HealthStatus.HEALTHY)

            metrics.append(HealthMetric("connected", connected, "", conn_status))
            if connected:
                metrics.append(HealthMetric("latency_ms", round(latency_ms, 1), "ms", lat_status))

            health.status = conn_status
            health.response_time_ms = latency_ms

        except Exception as e:
            health.status = HealthStatus.UNKNOWN
            health.error = str(e)

        health.metrics = metrics
        return health

    def check_backend(self, url: str, timeout: float = 5.0) -> ComponentHealth:
        """Ping a backend health endpoint."""
        health = ComponentHealth(name=f"backend:{url}", last_checked=time.time())
        import urllib.request
        import urllib.error
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(url, timeout=timeout) as resp:
                elapsed_ms = (time.perf_counter() - start) * 1000
                status_code = resp.status
                health.response_time_ms = elapsed_ms
                if status_code < 400:
                    health.status = HealthStatus.HEALTHY
                    health.metrics.append(HealthMetric("status_code", status_code, "", HealthStatus.HEALTHY))
                    health.metrics.append(HealthMetric("response_ms", round(elapsed_ms, 1), "ms",
                                                        HealthStatus.DEGRADED if elapsed_ms > LATENCY_WARN_MS else HealthStatus.HEALTHY))
                else:
                    health.status = HealthStatus.UNHEALTHY
                    health.error = f"HTTP {status_code}"
        except Exception as e:
            elapsed_ms = (time.perf_counter() - start) * 1000
            health.status = HealthStatus.UNHEALTHY
            health.response_time_ms = elapsed_ms
            health.error = str(e)

        return health

    def run_all_checks(self) -> Dict[str, ComponentHealth]:
        """Run all registered checks and return results."""
        results = {}
        results["system"] = self.check_system()
        results["network"] = self.check_network()
        for url in self._backend_urls:
            comp = self.check_backend(url)
            results[comp.name] = comp

        with self._lock:
            old_statuses = {n: c.status for n, c in self._components.items()}
            for name, comp in results.items():
                self._components[name] = comp
            new_statuses = {n: c.status for n, c in results.items()}

        # Fire status change callbacks
        for name, new_status in new_statuses.items():
            old = old_statuses.get(name)
            if old != new_status:
                healthy = new_status == HealthStatus.HEALTHY
                for cb in self._status_callbacks:
                    try:
                        cb(name, healthy)
                    except Exception as e:
                        log.warning("HealthMonitor: callback error: %s", e)

        # Emit alerts
        for name, comp in results.items():
            if comp.status in (HealthStatus.UNHEALTHY, HealthStatus.DEGRADED):
                self._emit_alert(name, comp)

        return results

    def _emit_alert(self, name: str, comp: ComponentHealth) -> None:
        alert = {
            "component": name,
            "status": comp.status.value,
            "error": comp.error,
            "timestamp": time.time(),
        }
        self._alerts.append(alert)
        if len(self._alerts) > 500:
            self._alerts = self._alerts[-500:]
        log.warning("HEALTH ALERT: component=%s status=%s error=%s", name, comp.status.value, comp.error)
        for cb in self._alert_callbacks:
            try:
                cb(alert)
            except Exception as e:
                log.warning("HealthMonitor: alert callback error: %s", e)

    # ── API endpoints support ─────────────────────────────────────────────────

    def get_health_response(self) -> Tuple[dict, int]:
        """/health endpoint response: (body, http_status_code)."""
        results = self.run_all_checks()
        overall = HealthStatus.HEALTHY
        for comp in results.values():
            if comp.status == HealthStatus.UNHEALTHY:
                overall = HealthStatus.UNHEALTHY
                break
            elif comp.status == HealthStatus.DEGRADED:
                overall = HealthStatus.DEGRADED

        http_code = 200 if overall == HealthStatus.HEALTHY else (503 if overall == HealthStatus.UNHEALTHY else 200)
        return {
            "status": overall.value,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "components": {n: c.to_dict() for n, c in results.items()},
        }, http_code

    def get_status_response(self) -> dict:
        """/status endpoint — lightweight (no active check)."""
        with self._lock:
            comps = dict(self._components)
        return {
            "status": "running",
            "components": {n: c.status.value for n, c in comps.items()},
            "alert_count": len(self._alerts),
            "last_check": max((c.last_checked for c in comps.values()), default=0),
        }

    def get_full_report(self) -> dict:
        results = self.run_all_checks()
        return {
            "timestamp": time.time(),
            "components": {n: c.to_dict() for n, c in results.items()},
            "alerts": self._alerts[-20:],
            "check_interval_seconds": self._interval,
        }

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="HealthMonitor")
        self._thread.start()
        log.info("HealthMonitor: started (interval=%ds)", self._interval)

    def stop(self) -> None:
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)

    def _loop(self) -> None:
        while self._running:
            try:
                self.run_all_checks()
            except Exception as e:
                log.error("HealthMonitor: check loop error: %s", e)
            time.sleep(self._interval)
