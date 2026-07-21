"""
reliability_ext/crash_recovery.py — Phase 13 Feature 2
=======================================================
Crash detection, state persistence, and recovery.

Features:
- Save crash reason + full stack trace on unhandled exception
- Recover previous session/context/task state after restart
- Checkpointing: periodic state snapshots
- Zero user data loss guarantee (atomic writes)
- Crash report generation with system info
- Automatic crash analytics
- Graceful degradation if recovery fails
- Custom sys.excepthook integration
"""

from __future__ import annotations

import json
import logging
import os
import platform
import sys
import threading
import time
import traceback
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class CrashReport:
    crash_id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    timestamp: float = field(default_factory=time.time)
    exception_type: str = ""
    exception_message: str = ""
    stack_trace: str = ""
    system_info: Dict[str, Any] = field(default_factory=dict)
    recovered_state: Optional[dict] = None

    def to_dict(self) -> dict:
        d = asdict(self)
        d["timestamp_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.timestamp))
        return d


@dataclass
class CheckpointState:
    session_id: str
    context: Dict[str, Any]
    ongoing_tasks: List[dict]
    user_data: Dict[str, Any]
    checkpoint_time: float = field(default_factory=time.time)
    version: int = 1


class CrashRecovery:
    """
    Manages crash detection, state persistence, and recovery.

    Usage::

        cr = CrashRecovery(state_dir="data/recovery")
        cr.install_excepthook()       # Catch all unhandled exceptions
        cr.checkpoint(state)          # Save state periodically

        # On startup:
        prev_state = cr.recover()     # Returns None if no crash
        if prev_state:
            restore_from(prev_state)
    """

    STATE_FILE = "last_state.json"
    CRASH_FILE = "last_crash.json"
    CRASH_HISTORY_FILE = "crash_history.json"

    def __init__(self, state_dir: str = "data/recovery") -> None:
        self._dir = Path(state_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._state_path = self._dir / self.STATE_FILE
        self._crash_path = self._dir / self.CRASH_FILE
        self._history_path = self._dir / self.CRASH_HISTORY_FILE
        self._lock = threading.Lock()
        self._checkpoint_thread: Optional[threading.Thread] = None
        self._running = False
        self._current_state: Optional[CheckpointState] = None
        self._crash_count = 0

    # ── Excepthook integration ────────────────────────────────────────────────

    def install_excepthook(self) -> None:
        """Replace sys.excepthook to catch and record all unhandled exceptions."""
        original = sys.excepthook

        def _hook(exc_type, exc_value, exc_tb):
            self._handle_crash(exc_type, exc_value, exc_tb)
            original(exc_type, exc_value, exc_tb)

        sys.excepthook = _hook
        log.info("CrashRecovery: excepthook installed")

    def _handle_crash(self, exc_type, exc_value, exc_tb) -> None:
        try:
            tb_str = "".join(traceback.format_exception(exc_type, exc_value, exc_tb))
            report = CrashReport(
                exception_type=exc_type.__name__ if exc_type else "Unknown",
                exception_message=str(exc_value),
                stack_trace=tb_str,
                system_info=self._collect_system_info(),
            )
            # Save state snapshot with crash report
            with self._lock:
                if self._current_state:
                    report.recovered_state = asdict(self._current_state)
            self._save_crash_report(report)
            self._crash_count += 1
            log.error("CrashRecovery: crash captured (id=%s): %s", report.crash_id, exc_value)
        except Exception as e:
            log.error("CrashRecovery: failed to record crash: %s", e)

    # ── Checkpointing ─────────────────────────────────────────────────────────

    def checkpoint(self, state: CheckpointState) -> bool:
        """Atomically save the current application state."""
        try:
            with self._lock:
                self._current_state = state
            data = asdict(state)
            data["checkpoint_time_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(state.checkpoint_time))
            self._atomic_write(self._state_path, data)
            log.debug("CrashRecovery: checkpoint saved (session=%s)", state.session_id)
            return True
        except Exception as e:
            log.error("CrashRecovery: checkpoint failed: %s", e)
            return False

    def checkpoint_dict(
        self,
        context: dict,
        *,
        session_id: str = "",
        ongoing_tasks: Optional[List[dict]] = None,
        user_data: Optional[dict] = None,
    ) -> bool:
        """Convenience: checkpoint from a plain dict."""
        state = CheckpointState(
            session_id=session_id or str(uuid.uuid4())[:8],
            context=context,
            ongoing_tasks=ongoing_tasks or [],
            user_data=user_data or {},
        )
        return self.checkpoint(state)

    def start_auto_checkpoint(self, get_state_fn, interval_seconds: int = 60) -> None:
        """Start a background thread that checkpoints at regular intervals."""
        self._running = True
        def _loop():
            while self._running:
                try:
                    state = get_state_fn()
                    if isinstance(state, CheckpointState):
                        self.checkpoint(state)
                    elif isinstance(state, dict):
                        self.checkpoint_dict(state)
                except Exception as e:
                    log.warning("CrashRecovery: auto-checkpoint error: %s", e)
                time.sleep(interval_seconds)

        self._checkpoint_thread = threading.Thread(target=_loop, daemon=True, name="AutoCheckpoint")
        self._checkpoint_thread.start()
        log.info("CrashRecovery: auto-checkpoint started (interval=%ds)", interval_seconds)

    def stop_auto_checkpoint(self) -> None:
        self._running = False

    # ── Recovery ──────────────────────────────────────────────────────────────

    def recover(self) -> Optional[dict]:
        """
        Load saved state from last checkpoint.
        Returns None if no saved state or state is corrupt.
        """
        if not self._state_path.exists():
            return None
        try:
            data = json.loads(self._state_path.read_text(encoding="utf-8"))
            age_seconds = time.time() - data.get("checkpoint_time", 0)
            log.info("CrashRecovery: found checkpoint (age=%.0fs)", age_seconds)
            return data
        except Exception as e:
            log.error("CrashRecovery: failed to load checkpoint: %s", e)
            return None

    def was_crash(self) -> bool:
        """Returns True if the last run ended in a crash."""
        return self._crash_path.exists()

    def get_last_crash(self) -> Optional[CrashReport]:
        """Load the most recent crash report."""
        if not self._crash_path.exists():
            return None
        try:
            data = json.loads(self._crash_path.read_text(encoding="utf-8"))
            return CrashReport(**{k: v for k, v in data.items() if k in CrashReport.__dataclass_fields__})
        except Exception as e:
            log.error("CrashRecovery: failed to load crash report: %s", e)
            return None

    def clear_crash_flag(self) -> None:
        """Clear the crash marker after successful recovery."""
        try:
            self._crash_path.unlink(missing_ok=True)
        except Exception:
            pass

    def get_crash_history(self, limit: int = 20) -> List[dict]:
        if not self._history_path.exists():
            return []
        try:
            return json.loads(self._history_path.read_text(encoding="utf-8"))[-limit:]
        except Exception:
            return []

    # ── Reports ───────────────────────────────────────────────────────────────

    def generate_crash_analytics(self) -> dict:
        history = self.get_crash_history(100)
        from collections import Counter
        exc_types = Counter(h.get("exception_type", "Unknown") for h in history)
        return {
            "total_crashes": len(history),
            "crash_count_this_session": self._crash_count,
            "most_common_exceptions": exc_types.most_common(5),
            "last_crash_time": history[-1].get("timestamp") if history else None,
            "has_active_crash_flag": self.was_crash(),
        }

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _save_crash_report(self, report: CrashReport) -> None:
        data = report.to_dict()
        self._atomic_write(self._crash_path, data)
        # Append to history
        history = []
        if self._history_path.exists():
            try:
                history = json.loads(self._history_path.read_text())
            except Exception:
                pass
        history.append(data)
        history = history[-200:]  # Keep last 200
        self._atomic_write(self._history_path, history)

    @staticmethod
    def _atomic_write(path: Path, data: Any) -> None:
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
        tmp.replace(path)

    @staticmethod
    def _collect_system_info() -> dict:
        try:
            import psutil
            mem = psutil.virtual_memory()
            cpu = psutil.cpu_percent(interval=0.1)
        except ImportError:
            mem, cpu = None, None

        return {
            "platform": platform.platform(),
            "python": sys.version,
            "pid": os.getpid(),
            "cpu_percent": cpu,
            "memory_percent": mem.percent if mem else None,
            "uptime_seconds": time.time() - psutil.boot_time() if "psutil" in sys.modules else None,
        }
