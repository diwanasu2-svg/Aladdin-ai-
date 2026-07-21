"""
security/audit_logger.py — Phase 12 Feature 10
===============================================
Tamper-resistant audit logging for security events.

Features:
- Structured JSON log entries with timestamp, user, endpoint, method, status
- HMAC-SHA256 chain for tamper detection
- Thread-safe append-only log file
- Log rotation by size/age
- Log retention policy (30–90 days)
- Queryable in-memory event buffer
- Sensitive-action highlighting
- Async-safe log shipping stub
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

AUDIT_HMAC_SECRET = os.environ.get("AUDIT_HMAC_SECRET", "audit-hmac-key-change-in-prod").encode()
MAX_BUFFER_SIZE = 10_000
LOG_ROTATION_MB = float(os.environ.get("AUDIT_LOG_ROTATION_MB", "50"))
LOG_RETENTION_DAYS = int(os.environ.get("AUDIT_LOG_RETENTION_DAYS", "30"))

SENSITIVE_EVENTS = frozenset({
    "login_failure", "access_denied", "secret_accessed", "shell_command",
    "user_delete", "settings_change", "token_revoked", "permission_grant",
    "secret_rotation_rotated", "rate_limited",
})


@dataclass
class AuditEntry:
    event: str
    user_id: str
    detail: str
    timestamp: float = field(default_factory=time.time)
    endpoint: str = ""
    method: str = ""
    status_code: int = 0
    client_ip: str = ""
    session_id: str = ""
    chain_hash: str = ""   # HMAC of this entry chained with previous
    sequence: int = 0

    def to_dict(self) -> dict:
        d = asdict(self)
        d["timestamp_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.timestamp))
        return d


class AuditLogger:
    """
    Append-only, HMAC-chained audit logger.

    Usage::

        al = AuditLogger(log_path="logs/audit.log")
        al.log("login_success", user_id="alice", detail="")
        al.log("access_denied", user_id="bob", detail="policy=delete resource=record/1")

        events = al.query(event_filter="login", since=time.time()-3600)
    """

    def __init__(
        self,
        log_path: str = "logs/audit.log",
        hmac_secret: Optional[bytes] = None,
        remote_shipper: Optional[Callable[[dict], None]] = None,
    ) -> None:
        self._log_path = Path(log_path)
        self._log_path.parent.mkdir(parents=True, exist_ok=True)
        self._hmac_secret = hmac_secret or AUDIT_HMAC_SECRET
        self._remote_shipper = remote_shipper
        self._buffer: List[AuditEntry] = []
        self._lock = threading.Lock()
        self._sequence = 0
        self._last_hash = ""
        self._rotation_bytes = int(LOG_ROTATION_MB * 1024 * 1024)

        # Schedule retention cleanup daily
        t = threading.Thread(target=self._retention_loop, daemon=True, name="AuditRetention")
        t.start()

        log.info("AuditLogger: ready (path=%s)", log_path)

    def log(
        self,
        event: str,
        *,
        user_id: str = "system",
        detail: str = "",
        endpoint: str = "",
        method: str = "",
        status_code: int = 0,
        client_ip: str = "",
        session_id: str = "",
    ) -> AuditEntry:
        """Record an audit event — thread-safe, appended to file and buffer."""
        with self._lock:
            self._sequence += 1
            entry = AuditEntry(
                event=event,
                user_id=user_id,
                detail=detail,
                endpoint=endpoint,
                method=method,
                status_code=status_code,
                client_ip=client_ip,
                session_id=session_id,
                sequence=self._sequence,
            )
            entry.chain_hash = self._compute_chain_hash(entry)
            self._last_hash = entry.chain_hash

            # Append to buffer
            self._buffer.append(entry)
            if len(self._buffer) > MAX_BUFFER_SIZE:
                self._buffer = self._buffer[-MAX_BUFFER_SIZE:]

            # Write to file
            self._write_entry(entry)

        if event in SENSITIVE_EVENTS:
            log.warning("AUDIT[SENSITIVE]: event=%s user=%s detail=%s", event, user_id, detail)
        else:
            log.info("AUDIT: event=%s user=%s", event, user_id)

        # Remote shipping (non-blocking)
        if self._remote_shipper:
            try:
                threading.Thread(target=self._remote_shipper, args=(entry.to_dict(),), daemon=True).start()
            except Exception as e:
                log.warning("AuditLogger: remote shipping error: %s", e)

        return entry

    def log_api_access(
        self, user_id: str, endpoint: str, method: str, status_code: int, client_ip: str = ""
    ) -> None:
        """Convenience: log an API access event."""
        self.log(
            "api_access",
            user_id=user_id,
            endpoint=endpoint,
            method=method,
            status_code=status_code,
            client_ip=client_ip,
        )

    def log_login(self, user_id: str, success: bool, client_ip: str = "") -> None:
        event = "login_success" if success else "login_failure"
        self.log(event, user_id=user_id, client_ip=client_ip,
                 detail=f"ip={client_ip} success={success}")

    def log_error(self, error: str, user_id: str = "system", stack_trace: str = "") -> None:
        detail = error
        if stack_trace:
            detail += f" | trace={stack_trace[:500]}"
        self.log("error", user_id=user_id, detail=detail)

    # ── Querying ──────────────────────────────────────────────────────────────

    def query(
        self,
        event_filter: str = "",
        user_id_filter: str = "",
        since: Optional[float] = None,
        until: Optional[float] = None,
        limit: int = 100,
    ) -> List[AuditEntry]:
        with self._lock:
            results = list(self._buffer)

        if event_filter:
            results = [e for e in results if event_filter.lower() in e.event.lower()]
        if user_id_filter:
            results = [e for e in results if e.user_id == user_id_filter]
        if since:
            results = [e for e in results if e.timestamp >= since]
        if until:
            results = [e for e in results if e.timestamp <= until]

        return results[-limit:]

    def verify_chain_integrity(self, entries: Optional[List[AuditEntry]] = None) -> Tuple[bool, List[int]]:
        """
        Verify HMAC chain — detects tampered log entries.
        Returns (all_valid, list_of_broken_sequence_numbers).
        """
        from typing import Tuple
        with self._lock:
            check_entries = entries or list(self._buffer)

        broken = []
        prev_hash = ""
        for entry in check_entries:
            expected = self._compute_chain_hash(entry, prev_hash_override=prev_hash)
            if not hmac.compare_digest(entry.chain_hash, expected):
                broken.append(entry.sequence)
            prev_hash = entry.chain_hash

        return len(broken) == 0, broken

    def get_security_summary(self) -> dict:
        with self._lock:
            buf = list(self._buffer)

        from collections import Counter
        event_counts = Counter(e.event for e in buf)
        failure_rate = event_counts.get("login_failure", 0)
        recent_denials = [e.to_dict() for e in buf if e.event == "access_denied"][-10:]

        return {
            "total_events": len(buf),
            "event_breakdown": dict(event_counts.most_common(20)),
            "login_failures": failure_rate,
            "recent_access_denials": recent_denials,
            "sensitive_events": [e.to_dict() for e in buf if e.event in SENSITIVE_EVENTS][-20:],
        }

    # ── Internal ──────────────────────────────────────────────────────────────

    def _compute_chain_hash(self, entry: AuditEntry, prev_hash_override: Optional[str] = None) -> str:
        prev = prev_hash_override if prev_hash_override is not None else self._last_hash
        payload = f"{prev}|{entry.sequence}|{entry.event}|{entry.user_id}|{entry.timestamp:.6f}|{entry.detail}"
        return hmac.new(self._hmac_secret, payload.encode(), hashlib.sha256).hexdigest()

    def _write_entry(self, entry: AuditEntry) -> None:
        try:
            # Rotate if needed
            if self._log_path.exists() and self._log_path.stat().st_size > self._rotation_bytes:
                self._rotate_log()
            with self._log_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry.to_dict(), default=str) + "\n")
        except Exception as e:
            log.error("AuditLogger: write failed: %s", e)

    def _rotate_log(self) -> None:
        archive = self._log_path.with_suffix(f".{int(time.time())}.log")
        try:
            self._log_path.rename(archive)
            log.info("AuditLogger: rotated log to %s", archive)
        except Exception as e:
            log.error("AuditLogger: rotation failed: %s", e)

    def _retention_loop(self) -> None:
        while True:
            time.sleep(86400)  # daily
            self._purge_old_logs()

    def _purge_old_logs(self) -> None:
        cutoff = time.time() - LOG_RETENTION_DAYS * 86400
        parent = self._log_path.parent
        purged = 0
        for f in parent.glob("*.log"):
            if f.stat().st_mtime < cutoff and f != self._log_path:
                try:
                    f.unlink()
                    purged += 1
                except Exception:
                    pass
        if purged:
            log.info("AuditLogger: purged %d old log files (retention=%dd)", purged, LOG_RETENTION_DAYS)
