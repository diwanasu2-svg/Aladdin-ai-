"""
security/secret_rotator.py — Phase 12 Feature 7
================================================
Automated secret rotation — scheduled replacement of API keys, tokens,
and credentials with zero downtime.

Features:
- Per-secret rotation schedule (30–90 day default)
- Background scheduler thread
- Generator callback per secret type
- Graceful degradation if new secret fails
- Rotation logging and audit trail
- Zero-downtime swap (new key validated before old is removed)
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)

DEFAULT_ROTATION_DAYS = int(__import__("os").environ.get("SECRET_ROTATION_DAYS", "30"))


@dataclass
class RotationRecord:
    name: str
    generator_fn: Optional[Callable[[], str]] = None  # returns new secret value
    validator_fn: Optional[Callable[[str], bool]] = None  # validates new secret
    interval_seconds: float = DEFAULT_ROTATION_DAYS * 86400
    last_rotated: float = field(default_factory=time.time)
    rotation_count: int = 0
    last_error: str = ""
    enabled: bool = True


class SecretRotator:
    """
    Manages periodic rotation of secrets stored in KeyStorage.

    Usage::

        rotator = SecretRotator(key_storage)
        rotator.register(
            "OPENAI_API_KEY",
            generator_fn=fetch_new_openai_key,
            validator_fn=lambda k: k.startswith("sk-"),
            interval_days=30,
        )
        rotator.start()
    """

    def __init__(self, key_storage=None, audit_logger=None) -> None:
        self._key_storage = key_storage
        self._audit_logger = audit_logger
        self._records: Dict[str, RotationRecord] = {}
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._rotation_history: List[dict] = []

    def register(
        self,
        name: str,
        *,
        generator_fn: Optional[Callable[[], str]] = None,
        validator_fn: Optional[Callable[[str], bool]] = None,
        interval_days: int = DEFAULT_ROTATION_DAYS,
        enabled: bool = True,
    ) -> None:
        """Register a secret for automatic rotation."""
        with self._lock:
            self._records[name] = RotationRecord(
                name=name,
                generator_fn=generator_fn,
                validator_fn=validator_fn,
                interval_seconds=interval_days * 86400,
                enabled=enabled,
            )
        log.info("SecretRotator: registered '%s' (interval=%dd, generator=%s)",
                 name, interval_days, "yes" if generator_fn else "no")

    def unregister(self, name: str) -> None:
        with self._lock:
            self._records.pop(name, None)

    def rotate_now(self, name: str) -> bool:
        """Force immediate rotation of a specific secret. Returns True on success."""
        with self._lock:
            record = self._records.get(name)
        if record is None:
            log.error("SecretRotator: no record for '%s'", name)
            return False
        return self._do_rotate(record)

    def rotate_all_due(self) -> Tuple[int, int]:
        """Rotate all secrets that are past their interval. Returns (success, failed)."""
        now = time.time()
        success = failed = 0
        with self._lock:
            due = [r for r in self._records.values() if r.enabled and now - r.last_rotated >= r.interval_seconds]
        for record in due:
            if self._do_rotate(record):
                success += 1
            else:
                failed += 1
        return success, failed

    def _do_rotate(self, record: RotationRecord) -> bool:
        log.info("SecretRotator: rotating '%s' (rotation #%d)", record.name, record.rotation_count + 1)
        rotation_id = str(uuid.uuid4())[:8]

        if record.generator_fn is None:
            log.warning("SecretRotator: no generator_fn for '%s' — cannot auto-rotate", record.name)
            return False

        try:
            new_value = record.generator_fn()
        except Exception as e:
            record.last_error = str(e)
            self._log_event(record.name, rotation_id, "generate_failed", str(e))
            log.error("SecretRotator: generate failed for '%s': %s", record.name, e)
            return False

        # Validate new secret before replacing
        if record.validator_fn:
            try:
                valid = record.validator_fn(new_value)
            except Exception as e:
                valid = False
                log.error("SecretRotator: validator raised for '%s': %s", record.name, e)
            if not valid:
                record.last_error = "validation failed"
                self._log_event(record.name, rotation_id, "validation_failed", "")
                log.error("SecretRotator: new secret for '%s' failed validation — keeping old", record.name)
                return False  # Graceful degradation: keep old secret

        # Store new secret
        try:
            if self._key_storage:
                self._key_storage.rotate(record.name, new_value)
            # Also update environment variable for running process
            import os
            os.environ[record.name] = new_value
        except Exception as e:
            record.last_error = str(e)
            self._log_event(record.name, rotation_id, "store_failed", str(e))
            log.error("SecretRotator: store failed for '%s': %s", record.name, e)
            return False

        record.rotation_count += 1
        record.last_rotated = time.time()
        record.last_error = ""
        self._log_event(record.name, rotation_id, "rotated", f"rotation_count={record.rotation_count}")
        log.info("SecretRotator: successfully rotated '%s' (total=%d)", record.name, record.rotation_count)
        return True

    def _log_event(self, name: str, rotation_id: str, event: str, detail: str) -> None:
        entry = {
            "timestamp": time.time(),
            "secret": name,
            "rotation_id": rotation_id,
            "event": event,
            "detail": detail,
        }
        self._rotation_history.append(entry)
        # Keep last 500 entries
        if len(self._rotation_history) > 500:
            self._rotation_history = self._rotation_history[-500:]
        if self._audit_logger:
            self._audit_logger.log(f"secret_rotation_{event}", detail=f"secret={name} id={rotation_id} {detail}")

    def get_history(self, secret_name: Optional[str] = None, limit: int = 50) -> List[dict]:
        history = self._rotation_history
        if secret_name:
            history = [h for h in history if h["secret"] == secret_name]
        return history[-limit:]

    def get_status(self) -> List[dict]:
        now = time.time()
        with self._lock:
            return [
                {
                    "name": r.name,
                    "enabled": r.enabled,
                    "rotation_count": r.rotation_count,
                    "last_rotated_ago_hours": round((now - r.last_rotated) / 3600, 1),
                    "next_rotation_hours": round((r.interval_seconds - (now - r.last_rotated)) / 3600, 1),
                    "last_error": r.last_error,
                    "has_generator": r.generator_fn is not None,
                }
                for r in self._records.values()
            ]

    def start(self, check_interval: int = 3600) -> None:
        """Start background rotation scheduler (checks every hour by default)."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(
            target=self._scheduler_loop, args=(check_interval,), daemon=True, name="SecretRotator"
        )
        self._thread.start()
        log.info("SecretRotator: scheduler started (check_interval=%ds)", check_interval)

    def stop(self) -> None:
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)

    def _scheduler_loop(self, interval: int) -> None:
        while self._running:
            try:
                ok, failed = self.rotate_all_due()
                if ok or failed:
                    log.info("SecretRotator: scheduled run — %d rotated, %d failed", ok, failed)
            except Exception as e:
                log.error("SecretRotator: scheduler error: %s", e)
            time.sleep(interval)
