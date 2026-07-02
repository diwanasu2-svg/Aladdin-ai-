"""production/crashlytics_integration.py — Phase 15, Feature 8: Crash Reporting.

Firebase Crashlytics integration with stack traces, device info,
non-fatal reporting, and a crash analytics interface.
"""

from __future__ import annotations

import logging
import os
import platform
import sys
import threading
import time
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class CrashReport:
    exception_type: str
    message: str
    stack_trace: str
    is_fatal: bool
    device_info: Dict[str, str]
    custom_keys: Dict[str, str] = field(default_factory=dict)
    timestamp: float = field(default_factory=time.time)
    session_id: str = ""
    user_id: str = ""
    version: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "exception_type": self.exception_type,
            "message": self.message,
            "stack_trace": self.stack_trace,
            "is_fatal": self.is_fatal,
            "device_info": self.device_info,
            "custom_keys": self.custom_keys,
            "timestamp": self.timestamp,
            "session_id": self.session_id,
            "user_id": self.user_id,
            "version": self.version,
        }


def _get_device_info() -> Dict[str, str]:
    """Collect device/runtime metadata for crash reports."""
    info: Dict[str, str] = {
        "os": platform.system(),
        "os_version": platform.version(),
        "machine": platform.machine(),
        "python_version": sys.version,
        "hostname": platform.node(),
    }
    if os.path.exists("/system/build.prop"):
        info["platform"] = "android"
        try:
            with open("/system/build.prop") as f:
                for line in f:
                    if line.startswith("ro.product.model="):
                        info["device_model"] = line.split("=", 1)[1].strip()
                    elif line.startswith("ro.build.version.release="):
                        info["android_version"] = line.split("=", 1)[1].strip()
        except Exception:
            pass
    else:
        info["platform"] = platform.system().lower()
    return info


class CrashQueue:
    """Thread-safe queue for buffering crash reports before upload."""

    def __init__(self, max_size: int = 500) -> None:
        self._queue: List[CrashReport] = []
        self._lock = threading.Lock()
        self._max_size = max_size

    def push(self, report: CrashReport) -> None:
        with self._lock:
            if len(self._queue) < self._max_size:
                self._queue.append(report)

    def drain(self) -> List[CrashReport]:
        with self._lock:
            items = list(self._queue)
            self._queue.clear()
            return items

    def __len__(self) -> int:
        with self._lock:
            return len(self._queue)


class CrashlyticsIntegration:
    """Firebase Crashlytics wrapper with Python fallback local logging."""

    def __init__(
        self,
        app_version: str = "unknown",
        enable_firebase: bool = True,
        local_log_path: str = "logs/crashes.jsonl",
        user_id: str = "",
    ) -> None:
        self._version = app_version
        self._enable_firebase = enable_firebase
        self._local_log = local_log_path
        self._user_id = user_id
        self._device_info = _get_device_info()
        self._custom_keys: Dict[str, str] = {}
        self._queue = CrashQueue()
        self._session_id = self._new_session()
        self._upload_thread: Optional[threading.Thread] = None
        self._firebase_app = None

        # Ensure log directory
        os.makedirs(os.path.dirname(self._local_log), exist_ok=True)

        # Try to init Firebase
        if enable_firebase:
            self._init_firebase()

        # Install global exception hooks
        self._install_hooks()

        # Start background uploader
        self._start_uploader()

        log.info("[Crashlytics] Initialized | version=%s | session=%s",
                 app_version, self._session_id)

    @staticmethod
    def _new_session() -> str:
        import uuid
        return str(uuid.uuid4())[:8]

    def _init_firebase(self) -> None:
        try:
            import firebase_admin  # type: ignore
            from firebase_admin import crashlytics  # type: ignore
            cred_file = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
            if cred_file and os.path.exists(cred_file):
                cred = firebase_admin.credentials.Certificate(cred_file)
                self._firebase_app = firebase_admin.initialize_app(cred)
                log.info("[Crashlytics] Firebase initialized")
            else:
                log.info("[Crashlytics] No Firebase credentials — local-only mode")
                self._enable_firebase = False
        except ImportError:
            log.info("[Crashlytics] firebase-admin not installed — local-only mode")
            self._enable_firebase = False

    def _install_hooks(self) -> None:
        """Install sys.excepthook for fatal crashes."""
        original_hook = sys.excepthook

        def _hook(exc_type, exc_value, exc_tb):
            self.record_fatal(exc_value, exc_tb=exc_tb)
            original_hook(exc_type, exc_value, exc_tb)

        sys.excepthook = _hook

        # Threading exception handler (Python 3.8+)
        original_thread_hook = threading.excepthook

        def _thread_hook(args):
            self.record_fatal(args.exc_value)
            original_thread_hook(args)

        threading.excepthook = _thread_hook  # type: ignore[attr-defined]

    # ------------------------------------------------------------------
    # Reporting API
    # ------------------------------------------------------------------

    def set_custom_key(self, key: str, value: str) -> None:
        self._custom_keys[key] = str(value)

    def set_user_id(self, user_id: str) -> None:
        self._user_id = user_id

    def record_fatal(
        self,
        exception: BaseException,
        exc_tb: Any = None,
    ) -> None:
        tb = exc_tb or exception.__traceback__
        stack = "".join(traceback.format_exception(type(exception), exception, tb))
        report = self._build_report(exception, stack, is_fatal=True)
        self._enqueue(report)
        log.critical("[Crashlytics] FATAL: %s — %s", type(exception).__name__, exception)

    def record_non_fatal(
        self,
        exception: Exception,
        context: Optional[str] = None,
    ) -> None:
        stack = "".join(traceback.format_exception(type(exception), exception, exception.__traceback__))
        report = self._build_report(exception, stack, is_fatal=False)
        if context:
            report.custom_keys["context"] = context
        self._enqueue(report)
        log.warning("[Crashlytics] Non-fatal: %s — %s", type(exception).__name__, exception)

    def log_message(self, message: str, level: str = "INFO") -> None:
        """Attach a log message to the current session (sent with next crash)."""
        self.set_custom_key(f"log_{int(time.time())}", f"[{level}] {message[:200]}")

    def _build_report(self, exc: BaseException, stack: str, is_fatal: bool) -> CrashReport:
        return CrashReport(
            exception_type=type(exc).__name__,
            message=str(exc)[:500],
            stack_trace=stack[:5000],
            is_fatal=is_fatal,
            device_info=self._device_info,
            custom_keys=dict(self._custom_keys),
            session_id=self._session_id,
            user_id=self._user_id,
            version=self._version,
        )

    def _enqueue(self, report: CrashReport) -> None:
        self._queue.push(report)
        self._write_local(report)

    def _write_local(self, report: CrashReport) -> None:
        try:
            import json
            with open(self._local_log, "a") as f:
                f.write(json.dumps(report.to_dict()) + "\n")
        except Exception as exc:
            log.warning("[Crashlytics] Local write failed: %s", exc)

    # ------------------------------------------------------------------
    # Upload
    # ------------------------------------------------------------------

    def _start_uploader(self) -> None:
        def _loop() -> None:
            while True:
                time.sleep(30)
                self._flush()

        self._upload_thread = threading.Thread(target=_loop, daemon=True, name="CrashlyticsUploader")
        self._upload_thread.start()

    def _flush(self) -> None:
        reports = self._queue.drain()
        if not reports:
            return
        if self._enable_firebase and self._firebase_app:
            try:
                import firebase_admin.crashlytics as fb_crash  # type: ignore
                for r in reports:
                    fb_crash.report(r.to_dict())
                log.debug("[Crashlytics] Uploaded %d reports to Firebase", len(reports))
            except Exception as exc:
                log.warning("[Crashlytics] Firebase upload failed: %s", exc)
        else:
            log.debug("[Crashlytics] %d reports written locally (no Firebase)", len(reports))

    # ------------------------------------------------------------------
    # Analytics
    # ------------------------------------------------------------------

    def crash_summary(self, last_n: int = 100) -> Dict[str, Any]:
        """Read local log and return crash frequency summary."""
        from collections import Counter
        import json

        counts: Counter = Counter()
        total = 0
        fatals = 0

        try:
            with open(self._local_log) as f:
                lines = f.readlines()
            for line in lines[-last_n:]:
                try:
                    r = json.loads(line)
                    counts[r.get("exception_type", "unknown")] += 1
                    total += 1
                    if r.get("is_fatal"):
                        fatals += 1
                except Exception:
                    pass
        except FileNotFoundError:
            pass

        return {
            "total_crashes": total,
            "fatal_crashes": fatals,
            "non_fatal_crashes": total - fatals,
            "top_exceptions": counts.most_common(10),
            "session_id": self._session_id,
        }
