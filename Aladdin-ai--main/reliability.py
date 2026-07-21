"""
Phase 10 — Reliability System
================================
- UncaughtExceptionHandler (crash recovery)
- Watchdog monitoring with automatic restart
- Health checks every 5 minutes
- Self-test diagnostics suite
- Rotating log capture (logcat-style)
- Dependency validation on startup
- Configuration validation
- Graceful shutdown with resource cleanup
- Service watchdog timer
- Dead service detection
- Exponential backoff retry
"""

from __future__ import annotations

import gc
import logging
import logging.handlers
import os
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class HealthCheckResult:
    name: str
    ok: bool
    message: str = ""
    latency_ms: float = 0.0


@dataclass
class DiagnosticReport:
    timestamp: str
    health_checks: List[HealthCheckResult] = field(default_factory=list)
    dependency_ok: bool = True
    config_ok: bool = True
    ram_mb: float = 0.0
    cpu_pct: float = 0.0
    uptime_s: float = 0.0
    warnings: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)

    @property
    def healthy(self) -> bool:
        return (
            all(h.ok for h in self.health_checks)
            and self.dependency_ok
            and self.config_ok
        )


# ─────────────────────────────────────────────────────────────────────────────
# Crash handler


class CrashHandler:
    """Install a global UncaughtExceptionHandler for automatic crash recovery."""

    def __init__(
        self,
        log_dir: str = "logs",
        restart_fn: Optional[Callable[[], None]] = None,
    ):
        self._log_dir = Path(log_dir)
        self._log_dir.mkdir(parents=True, exist_ok=True)
        self._restart_fn = restart_fn
        self._crash_count = 0
        self._original_excepthook = sys.excepthook

    def install(self) -> None:
        sys.excepthook = self._handle_exception
        signal.signal(signal.SIGTERM, self._handle_signal)
        try:
            signal.signal(signal.SIGHUP, self._handle_signal)
        except AttributeError:
            pass  # Windows
        log.info("CrashHandler installed")

    def _handle_exception(self, exc_type, exc_value, exc_tb) -> None:
        self._crash_count += 1
        log.critical(
            "UNCAUGHT EXCEPTION #%d: %s: %s",
            self._crash_count,
            exc_type.__name__,
            exc_value,
            exc_info=(exc_type, exc_value, exc_tb),
        )
        self._write_crash_log(exc_type, exc_value, exc_tb)

        if self._crash_count <= 3 and self._restart_fn:
            delay = 2 ** (self._crash_count - 1)
            log.info("Auto-restarting in %ds (attempt %d/3)…", delay, self._crash_count)
            time.sleep(delay)
            try:
                self._restart_fn()
            except Exception as exc:
                log.error("Restart failed: %s", exc)
        else:
            log.critical("Max crashes reached — not restarting automatically")

    def _handle_signal(self, signum, frame) -> None:
        log.info("Signal %d received — initiating graceful shutdown", signum)
        raise SystemExit(0)

    def _write_crash_log(self, exc_type, exc_value, exc_tb) -> None:
        import traceback

        ts = time.strftime("%Y%m%d_%H%M%S")
        log_file = self._log_dir / f"crash_{ts}.log"
        try:
            with log_file.open("w") as f:
                f.write(f"Crash at {ts}\n")
                f.write(f"Exception: {exc_type.__name__}: {exc_value}\n\n")
                traceback.print_exception(exc_type, exc_value, exc_tb, file=f)
        except Exception:
            pass


# ─────────────────────────────────────────────────────────────────────────────
# Rotating log handler


def setup_rotating_logs(
    log_dir: str = "logs",
    max_bytes: int = 5 * 1024 * 1024,  # 5 MB
    backup_count: int = 5,
) -> None:
    """Attach a rotating file handler to the root logger."""
    log_path = Path(log_dir) / "aladdin.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handler = logging.handlers.RotatingFileHandler(
        str(log_path),
        maxBytes=max_bytes,
        backupCount=backup_count,
        encoding="utf-8",
    )
    handler.setFormatter(
        logging.Formatter(
            "%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    )
    logging.getLogger().addHandler(handler)
    log.info(
        "Rotating log: %s (max %d MB × %d)",
        log_path,
        max_bytes // 1024 // 1024,
        backup_count,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Dependency validator

_REQUIRED_PACKAGES = [
    ("numpy", "numpy"),
    ("requests", "requests"),
    ("yaml", "PyYAML"),
]

_OPTIONAL_PACKAGES = [
    ("sounddevice", "sounddevice"),
    ("faster_whisper", "faster-whisper"),
    ("whisper", "openai-whisper"),
]


def validate_dependencies() -> Tuple[bool, List[str]]:
    """Check required and optional Python packages. Returns (ok, warnings)."""
    warnings = []
    ok = True
    for module, pkg in _REQUIRED_PACKAGES:
        try:
            __import__(module)
        except ImportError:
            log.error("Required package missing: %s  (pip install %s)", module, pkg)
            ok = False
    for module, pkg in _OPTIONAL_PACKAGES:
        try:
            __import__(module)
        except ImportError:
            msg = f"Optional package absent: {module} (pip install {pkg})"
            log.warning(msg)
            warnings.append(msg)
    return ok, warnings


# ─────────────────────────────────────────────────────────────────────────────
# Config validator


def validate_config(cfg) -> Tuple[bool, List[str]]:
    """Validate critical config fields. Returns (ok, errors)."""
    errors = []
    ollama = getattr(cfg, "ollama", None)
    if ollama:
        host = getattr(ollama, "host", "")
        if not host:
            errors.append("ollama.host is not set")
    piper = getattr(cfg, "piper", None)
    if piper:
        mp = getattr(piper, "model_path", "")
        if mp and not Path(mp).exists():
            log.warning("Piper model not found: %s", mp)
    return len(errors) == 0, errors


# ─────────────────────────────────────────────────────────────────────────────
# Health checks


class HealthChecker:
    """Run registered health checks and return a DiagnosticReport."""

    def __init__(self, start_time: float = None):
        self._checks: List[Callable[[], HealthCheckResult]] = []
        self._start_time = start_time or time.monotonic()

    def register(self, check_fn: Callable[[], HealthCheckResult]) -> None:
        self._checks.append(check_fn)

    def run_all(self) -> DiagnosticReport:
        from datetime import datetime

        dep_ok, dep_warns = validate_dependencies()
        report = DiagnosticReport(
            timestamp=datetime.utcnow().isoformat() + "Z",
            dependency_ok=dep_ok,
            uptime_s=time.monotonic() - self._start_time,
            warnings=dep_warns,
        )
        try:
            from performance_optimizer import current_ram_mb, current_cpu_pct

            report.ram_mb = current_ram_mb()
            report.cpu_pct = current_cpu_pct()
        except Exception:
            pass

        for check_fn in self._checks:
            try:
                t0 = time.monotonic()
                result = check_fn()
                result.latency_ms = (time.monotonic() - t0) * 1000
                report.health_checks.append(result)
                if not result.ok:
                    report.errors.append(f"{result.name}: {result.message}")
            except Exception as exc:
                report.health_checks.append(
                    HealthCheckResult(
                        name=getattr(check_fn, "__name__", "unknown"),
                        ok=False,
                        message=str(exc),
                    )
                )
        return report


# ─────────────────────────────────────────────────────────────────────────────
# Watchdog


class ServiceWatchdog:
    """
    Monitors a service function and restarts it if it dies.
    Uses exponential back-off up to max_delay_s.
    """

    def __init__(
        self,
        name: str,
        service_fn: Callable[[], None],
        health_check: Optional[Callable[[], bool]] = None,
        check_interval_s: int = 300,  # 5 minutes
        max_delay_s: int = 60,
    ):
        self._name = name
        self._service_fn = service_fn
        self._health_check = health_check
        self._check_interval = check_interval_s
        self._max_delay = max_delay_s
        self._stop = threading.Event()
        self._service_thread: Optional[threading.Thread] = None
        self._restart_count = 0
        self._alive = threading.Event()

    def start(self) -> None:
        self._launch_service()
        watcher = threading.Thread(
            target=self._watchdog_loop, daemon=True, name=f"watchdog-{self._name}"
        )
        watcher.start()
        log.info("Watchdog started for '%s'", self._name)

    def stop(self) -> None:
        self._stop.set()

    def _launch_service(self) -> None:
        def _run():
            self._alive.set()
            try:
                self._service_fn()
            except Exception as exc:
                log.error("Service '%s' crashed: %s", self._name, exc)
            finally:
                self._alive.clear()

        self._service_thread = threading.Thread(
            target=_run, daemon=True, name=f"svc-{self._name}"
        )
        self._service_thread.start()

    def _watchdog_loop(self) -> None:
        while not self._stop.wait(timeout=self._check_interval):
            service_dead = (
                self._service_thread is None or not self._service_thread.is_alive()
            )
            health_bad = False
            if self._health_check and not service_dead:
                try:
                    health_bad = not self._health_check()
                except Exception as exc:
                    log.warning("Health check error: %s", exc)
                    health_bad = True

            if service_dead or health_bad:
                self._restart_count += 1
                delay = min(2**self._restart_count, self._max_delay)
                log.warning(
                    "Service '%s' %s — restarting in %ds (attempt %d)",
                    self._name,
                    "died" if service_dead else "unhealthy",
                    delay,
                    self._restart_count,
                )
                time.sleep(delay)
                self._launch_service()
            else:
                log.debug("Watchdog: '%s' is alive.", self._name)


# ─────────────────────────────────────────────────────────────────────────────
# Graceful shutdown


class GracefulShutdown:
    """Registers shutdown hooks and coordinates clean teardown."""

    def __init__(self):
        self._hooks: List[Callable[[], None]] = []
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)

    def register(self, fn: Callable[[], None]) -> None:
        self._hooks.append(fn)

    def _signal_handler(self, signum, frame) -> None:
        log.info("Shutdown signal %d — running %d hooks", signum, len(self._hooks))
        for hook in reversed(self._hooks):
            try:
                hook()
            except Exception as exc:
                log.error("Shutdown hook error: %s", exc)
        gc.collect()
        sys.exit(0)


# ─────────────────────────────────────────────────────────────────────────────
# Retry helper (exponential back-off)


def retry(
    fn: Callable,
    *args,
    max_attempts: int = 3,
    base_delay: float = 0.5,
    label: str = "",
    **kwargs,
):
    """Call fn(*args, **kwargs) with exponential back-off on exception."""
    last_exc: Optional[Exception] = None
    for attempt in range(1, max_attempts + 1):
        try:
            return fn(*args, **kwargs)
        except Exception as exc:
            last_exc = exc
            delay = base_delay * (2 ** (attempt - 1))
            log.warning(
                "%s attempt %d/%d failed: %s — retrying in %.1fs",
                label or fn.__name__,
                attempt,
                max_attempts,
                exc,
                delay,
            )
            if attempt < max_attempts:
                time.sleep(delay)
    raise last_exc


from typing import Tuple  # already imported above, safe to re-import
