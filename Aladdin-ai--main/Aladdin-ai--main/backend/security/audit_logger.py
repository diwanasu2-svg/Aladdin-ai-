"""
Phase 12 Item 7 — Audit Logging.
Comprehensive security event logging with structured output, log rotation, and analysis.
"""

import os
import json
import logging
import functools
import threading
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional
from dataclasses import dataclass, asdict, field
from pathlib import Path
from logging.handlers import RotatingFileHandler
from flask import request, g

# ── Audit event types ─────────────────────────────────────────────────────────
class AuditEvent:
    # Auth
    LOGIN_SUCCESS       = "auth.login.success"
    LOGIN_FAILURE       = "auth.login.failure"
    LOGOUT              = "auth.logout"
    TOKEN_REFRESH       = "auth.token.refresh"
    TOKEN_REVOKED       = "auth.token.revoked"
    PASSWORD_CHANGE     = "auth.password.change"

    # Access control
    ACCESS_DENIED       = "access.denied"
    RATE_LIMITED        = "access.rate_limited"
    PERMISSION_ESCALATE = "access.permission.escalate"

    # Data operations
    DATA_READ           = "data.read"
    DATA_WRITE          = "data.write"
    DATA_DELETE         = "data.delete"
    DATA_EXPORT         = "data.export"

    # Security
    SUSPICIOUS_ACTIVITY = "security.suspicious"
    API_KEY_USED        = "security.api_key.used"
    INJECTION_ATTEMPT   = "security.injection_attempt"

    # System
    SERVICE_START       = "system.service.start"
    SERVICE_STOP        = "system.service.stop"
    CONFIG_CHANGE       = "system.config.change"
    BACKUP_CREATED      = "system.backup.created"
    RESTORE_PERFORMED   = "system.restore.performed"


@dataclass
class AuditRecord:
    event_type:  str
    timestamp:   str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    user_id:     str = ""
    user_role:   str = ""
    ip_address:  str = ""
    user_agent:  str = ""
    endpoint:    str = ""
    method:      str = ""
    status_code: int = 0
    resource:    str = ""
    action:      str = ""
    result:      str = "success"
    details:     Dict[str, Any] = field(default_factory=dict)
    severity:    str = "info"   # info | warning | critical


class AuditLogger:
    """
    Structured audit logger that writes to:
    1. Rotating file (audit.log)
    2. Standard Python logger (for aggregation)
    3. In-memory ring buffer (last N events for quick analysis)
    """

    BUFFER_SIZE  = 1000
    LOG_DIR      = os.getenv("AUDIT_LOG_DIR", "logs/audit")
    LOG_FILE     = "audit.log"
    MAX_BYTES    = 10 * 1024 * 1024  # 10 MB per file
    BACKUP_COUNT = 5

    def __init__(self):
        self._buffer: List[AuditRecord] = []
        self._lock   = threading.Lock()
        self._logger = self._setup_file_logger()

    def _setup_file_logger(self) -> logging.Logger:
        Path(self.LOG_DIR).mkdir(parents=True, exist_ok=True)
        log_path = os.path.join(self.LOG_DIR, self.LOG_FILE)
        handler  = RotatingFileHandler(log_path, maxBytes=self.MAX_BYTES, backupCount=self.BACKUP_COUNT)
        handler.setFormatter(logging.Formatter("%(message)s"))  # raw JSON
        logger   = logging.getLogger("aladdin.audit")
        logger.setLevel(logging.INFO)
        logger.addHandler(handler)
        logger.propagate = False
        return logger

    def log(self, record: AuditRecord) -> None:
        """Write a structured audit record."""
        line = json.dumps(asdict(record), default=str, ensure_ascii=False)
        self._logger.info(line)

        with self._lock:
            self._buffer.append(record)
            if len(self._buffer) > self.BUFFER_SIZE:
                self._buffer.pop(0)

        # Also emit to standard logging for aggregation
        level = {"info": logging.INFO, "warning": logging.WARNING,
                 "critical": logging.CRITICAL}.get(record.severity, logging.INFO)
        logging.getLogger(__name__).log(
            level, "[AUDIT] %s user=%s ip=%s → %s",
            record.event_type, record.user_id, record.ip_address, record.result
        )

    def log_event(
        self, event_type: str, user_id: str = "", result: str = "success",
        severity: str = "info", resource: str = "", action: str = "",
        details: Optional[Dict] = None, **kwargs
    ) -> None:
        """Convenience method to log an event with Flask request context."""
        try:
            ip         = request.remote_addr or "" if request else ""
            user_agent = request.headers.get("User-Agent", "")[:200] if request else ""
            endpoint   = request.path or "" if request else ""
            method     = request.method or "" if request else ""
        except RuntimeError:
            ip = user_agent = endpoint = method = ""

        uid   = user_id or getattr(g, "user_id", "") if not user_id else user_id
        role  = getattr(g, "user_role", "") or ""

        record = AuditRecord(
            event_type  = event_type,
            user_id     = uid,
            user_role   = role,
            ip_address  = ip,
            user_agent  = user_agent,
            endpoint    = endpoint,
            method      = method,
            result      = result,
            severity    = severity,
            resource    = resource,
            action      = action,
            details     = details or {},
        )
        self.log(record)

    # ── Analysis helpers ──────────────────────────────────────────────────────
    def get_recent(self, n: int = 50, event_type: Optional[str] = None) -> List[AuditRecord]:
        with self._lock:
            events = list(reversed(self._buffer))
        if event_type:
            events = [e for e in events if e.event_type == event_type]
        return events[:n]

    def get_failed_logins(self, user_id: str, minutes: int = 30) -> int:
        cutoff = datetime.now(timezone.utc).timestamp() - minutes * 60
        with self._lock:
            return sum(
                1 for e in self._buffer
                if e.event_type == AuditEvent.LOGIN_FAILURE
                and e.user_id == user_id
                and datetime.fromisoformat(e.timestamp).timestamp() > cutoff
            )

    def get_rate_limited_ips(self, minutes: int = 5) -> List[str]:
        cutoff = datetime.now(timezone.utc).timestamp() - minutes * 60
        with self._lock:
            return list({
                e.ip_address for e in self._buffer
                if e.event_type == AuditEvent.RATE_LIMITED
                and datetime.fromisoformat(e.timestamp).timestamp() > cutoff
            })

    def summary(self) -> Dict[str, Any]:
        with self._lock:
            buf = list(self._buffer)
        counts: Dict[str, int] = {}
        for e in buf:
            counts[e.event_type] = counts.get(e.event_type, 0) + 1
        failures = sum(1 for e in buf if e.result == "failure")
        return {
            "total_events": len(buf),
            "failure_count": failures,
            "event_counts": counts,
            "unique_users": len({e.user_id for e in buf if e.user_id}),
            "unique_ips":   len({e.ip_address for e in buf if e.ip_address}),
        }


# ── Global singleton + decorator ──────────────────────────────────────────────
_audit = AuditLogger()


def get_audit_logger() -> AuditLogger:
    return _audit


def audit_log(event_type: str, severity: str = "info", resource: str = "") -> Callable:
    """Decorator: automatically log an audit event when a route is called."""
    def decorator(f: Callable) -> Callable:
        @functools.wraps(f)
        def wrapper(*args, **kwargs):
            try:
                result = f(*args, **kwargs)
                status = getattr(result, "status_code", 200) if hasattr(result, "status_code") else 200
                _audit.log_event(
                    event_type=event_type, result="success",
                    severity=severity, resource=resource,
                    details={"status_code": status}
                )
                return result
            except Exception as e:
                _audit.log_event(
                    event_type=event_type, result="failure",
                    severity="warning", resource=resource,
                    details={"error": str(e)}
                )
                raise
        return wrapper
    return decorator
