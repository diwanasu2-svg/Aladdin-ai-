"""
reliability_ext/logging_system.py — Phase 13 Feature 9
======================================================
Structured JSON logging for all Aladdin components.

Features:
- Structured JSON logging (compatible with ELK, Splunk, Datadog)
- Log all AI events, voice pipeline, tool execution, API requests
- Log levels: DEBUG, INFO, WARNING, ERROR, CRITICAL
- Filter and search by date, level, component
- Remote log shipping stub (Logstash / HTTP)
- Log rotation (size-based, daily)
- Correlation IDs for request tracing
- Context managers for component-scoped logging
"""

from __future__ import annotations

import json
import logging
import logging.handlers
import os
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

LOG_DIR = os.environ.get("LOG_DIR", "logs")
LOG_MAX_BYTES = int(os.environ.get("LOG_MAX_BYTES", str(50 * 1024 * 1024)))  # 50 MB
LOG_BACKUP_COUNT = int(os.environ.get("LOG_BACKUP_COUNT", "10"))
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()


# ── JSON formatter ────────────────────────────────────────────────────────────

class JSONFormatter(logging.Formatter):
    """Format log records as JSON lines."""

    def __init__(self, component: str = "aladdin") -> None:
        super().__init__()
        self._component = component

    def format(self, record: logging.LogRecord) -> str:
        entry: Dict[str, Any] = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)),
            "timestamp_ms": int(record.created * 1000),
            "level": record.levelname,
            "component": getattr(record, "component", self._component),
            "logger": record.name,
            "message": record.getMessage(),
            "pid": os.getpid(),
            "thread": record.threadName,
        }
        # Correlation ID from thread-local
        corr_id = getattr(_correlation_ctx, "correlation_id", None)
        if corr_id:
            entry["correlation_id"] = corr_id

        # Extra structured fields
        for key in ("user_id", "session_id", "tool", "endpoint", "duration_ms",
                    "status_code", "event_type", "ai_model", "error_type"):
            val = getattr(record, key, None)
            if val is not None:
                entry[key] = val

        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        if record.stack_info:
            entry["stack_info"] = record.stack_info

        return json.dumps(entry, default=str, ensure_ascii=False)


# ── Thread-local correlation context ─────────────────────────────────────────

_correlation_ctx = threading.local()


@contextmanager
def correlation_id(cid: Optional[str] = None):
    """Context manager: attach a correlation ID to all logs within this scope."""
    _correlation_ctx.correlation_id = cid or str(uuid.uuid4())[:8]
    try:
        yield _correlation_ctx.correlation_id
    finally:
        _correlation_ctx.correlation_id = None


# ── LoggingSystem ─────────────────────────────────────────────────────────────

class LoggingSystem:
    """
    Configures and manages structured JSON logging for Aladdin.

    Usage::

        ls = LoggingSystem()
        ls.configure()

        ai_log  = ls.get_logger("ai_engine")
        vox_log = ls.get_logger("voice")

        ai_log.info("Query processed", extra={"event_type": "ai_query", "duration_ms": 123})

        with correlation_id("req-abc"):
            ai_log.info("Traced request")
    """

    def __init__(
        self,
        log_dir: str = LOG_DIR,
        level: str = LOG_LEVEL,
        json_format: bool = True,
        remote_shipper: Optional[Callable[[dict], None]] = None,
    ) -> None:
        self._log_dir = Path(log_dir)
        self._log_dir.mkdir(parents=True, exist_ok=True)
        self._level = getattr(logging, level, logging.INFO)
        self._json_format = json_format
        self._remote_shipper = remote_shipper
        self._configured = False
        self._loggers: Dict[str, logging.Logger] = {}

    def configure(self) -> None:
        """Set up root logging with JSON formatter + file handlers."""
        if self._configured:
            return

        root = logging.getLogger()
        root.setLevel(self._level)

        # Remove existing handlers
        root.handlers.clear()

        formatter = JSONFormatter() if self._json_format else logging.Formatter(
            "%(asctime)s %(levelname)-8s %(name)s — %(message)s"
        )

        # Console handler
        console = logging.StreamHandler()
        console.setFormatter(formatter)
        console.setLevel(self._level)
        root.addHandler(console)

        # Rotating file handler — main log
        main_log = self._log_dir / "aladdin.log"
        file_handler = logging.handlers.RotatingFileHandler(
            str(main_log), maxBytes=LOG_MAX_BYTES, backupCount=LOG_BACKUP_COUNT, encoding="utf-8"
        )
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)

        # Error-only log
        error_log = self._log_dir / "errors.log"
        error_handler = logging.handlers.RotatingFileHandler(
            str(error_log), maxBytes=LOG_MAX_BYTES // 5, backupCount=5, encoding="utf-8"
        )
        error_handler.setLevel(logging.ERROR)
        error_handler.setFormatter(formatter)
        root.addHandler(error_handler)

        # Daily backup log
        daily_log = self._log_dir / "aladdin_daily.log"
        daily_handler = logging.handlers.TimedRotatingFileHandler(
            str(daily_log), when="midnight", backupCount=LOG_BACKUP_COUNT, encoding="utf-8", utc=True
        )
        daily_handler.setFormatter(formatter)
        root.addHandler(daily_handler)

        if self._remote_shipper:
            root.addHandler(_RemoteShipperHandler(self._remote_shipper))

        self._configured = True
        logging.info("LoggingSystem: configured (level=%s json=%s dir=%s)", LOG_LEVEL, self._json_format, self._log_dir)

    def get_logger(self, component: str, *, level: Optional[str] = None) -> "ComponentLogger":
        """Return a component-specific logger."""
        logger = logging.getLogger(f"aladdin.{component}")
        if level:
            logger.setLevel(getattr(logging, level.upper(), logging.INFO))
        return ComponentLogger(logger, component)

    # ── Specialised log methods ───────────────────────────────────────────────

    def log_ai_event(
        self,
        event_type: str,
        model: str,
        query: str,
        response_len: int = 0,
        duration_ms: float = 0,
        user_id: str = "",
    ) -> None:
        logging.getLogger("aladdin.ai").info(
            "AI event: %s", event_type,
            extra={"event_type": event_type, "ai_model": model,
                   "duration_ms": duration_ms, "user_id": user_id,
                   "component": "ai_engine"},
        )

    def log_voice_event(self, stage: str, duration_ms: float = 0, language: str = "") -> None:
        logging.getLogger("aladdin.voice").info(
            "Voice: %s", stage,
            extra={"event_type": f"voice_{stage}", "duration_ms": duration_ms,
                   "component": "voice", "language": language},
        )

    def log_tool_execution(
        self, tool: str, params: dict, result: str, duration_ms: float = 0, success: bool = True
    ) -> None:
        level = logging.INFO if success else logging.WARNING
        logging.getLogger("aladdin.tools").log(
            level, "Tool: %s %s", tool, "OK" if success else "FAILED",
            extra={"event_type": "tool_exec", "tool": tool,
                   "duration_ms": duration_ms, "component": "tools"},
        )

    def log_api_request(
        self, endpoint: str, method: str, status_code: int, duration_ms: float, user_id: str = ""
    ) -> None:
        level = logging.WARNING if status_code >= 400 else logging.INFO
        logging.getLogger("aladdin.api").log(
            level, "API %s %s → %d", method, endpoint, status_code,
            extra={"event_type": "api_request", "endpoint": endpoint, "method": method,
                   "status_code": status_code, "duration_ms": duration_ms,
                   "user_id": user_id, "component": "api"},
        )

    # ── Log analysis ──────────────────────────────────────────────────────────

    def search_logs(
        self,
        keyword: str,
        log_file: Optional[str] = None,
        level: Optional[str] = None,
        since_minutes: int = 60,
        limit: int = 100,
    ) -> List[dict]:
        log_path = Path(log_file) if log_file else self._log_dir / "aladdin.log"
        if not log_path.exists():
            return []

        cutoff = time.time() - since_minutes * 60
        results = []

        try:
            with log_path.open("r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    try:
                        entry = json.loads(line.strip())
                    except json.JSONDecodeError:
                        continue

                    ts = entry.get("timestamp_ms", 0) / 1000
                    if ts < cutoff:
                        continue
                    if level and entry.get("level", "").upper() != level.upper():
                        continue
                    if keyword and keyword.lower() not in line.lower():
                        continue
                    results.append(entry)
        except Exception as e:
            log.error("LoggingSystem: search error: %s", e)

        return results[-limit:]

    def get_log_stats(self) -> dict:
        """Return log file sizes and entry counts."""
        stats = {}
        for log_file in self._log_dir.glob("*.log"):
            try:
                size = log_file.stat().st_size
                with log_file.open("r", encoding="utf-8", errors="replace") as f:
                    lines = sum(1 for _ in f)
                stats[log_file.name] = {"size_mb": round(size / 1_048_576, 2), "lines": lines}
            except Exception:
                pass
        return stats


# ── ComponentLogger ───────────────────────────────────────────────────────────

class ComponentLogger:
    """Wrapper adding component context to standard logger."""

    def __init__(self, logger: logging.Logger, component: str) -> None:
        self._logger = logger
        self._component = component

    def _extra(self, kwargs: dict) -> dict:
        extra = kwargs.pop("extra", {})
        extra.setdefault("component", self._component)
        return extra

    def debug(self, msg: str, *args, **kwargs) -> None:
        self._logger.debug(msg, *args, extra=self._extra(kwargs), **kwargs)

    def info(self, msg: str, *args, **kwargs) -> None:
        self._logger.info(msg, *args, extra=self._extra(kwargs), **kwargs)

    def warning(self, msg: str, *args, **kwargs) -> None:
        self._logger.warning(msg, *args, extra=self._extra(kwargs), **kwargs)

    def error(self, msg: str, *args, **kwargs) -> None:
        self._logger.error(msg, *args, extra=self._extra(kwargs), **kwargs)

    def critical(self, msg: str, *args, **kwargs) -> None:
        self._logger.critical(msg, *args, extra=self._extra(kwargs), **kwargs)

    def exception(self, msg: str, *args, **kwargs) -> None:
        self._logger.exception(msg, *args, extra=self._extra(kwargs), **kwargs)


# ── Remote shipper handler ────────────────────────────────────────────────────

class _RemoteShipperHandler(logging.Handler):
    def __init__(self, shipper: Callable[[dict], None]) -> None:
        super().__init__()
        self._shipper = shipper

    def emit(self, record: logging.LogRecord) -> None:
        try:
            entry = json.loads(self.format(record)) if isinstance(self.formatter, JSONFormatter) else {
                "level": record.levelname, "message": record.getMessage(), "timestamp": record.created
            }
            threading.Thread(target=self._shipper, args=(entry,), daemon=True).start()
        except Exception:
            self.handleError(record)
