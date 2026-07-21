"""
security/permission_auditor.py — Phase 12 Feature 8
====================================================
Real-time permission tracking, dangerous-permission auditing,
unused-permission detection, and auto-revocation recommendations.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Set

log = logging.getLogger(__name__)

DANGEROUS_PERMISSIONS = frozenset({
    "admin","shell_command","secret_access","file_write",
    "user_delete","settings_change","memory_delete",
    "permission_grant","permission_revoke","debug_access",
})

PERMISSION_EXPLANATIONS: Dict[str, str] = {
    "read_memory":      "Access conversation history for context-aware responses.",
    "write_memory":     "Save new information to improve future interactions.",
    "admin":            "Full system management — restricted to administrators.",
    "shell_command":    "Execute system commands — admin-only maintenance.",
    "secret_access":    "Read API keys/credentials — admin-only secure operation.",
    "file_write":       "Save files to device storage.",
    "file_read":        "Read files from device storage.",
    "settings_change":  "Modify preferences and system configuration.",
    "user_delete":      "Remove user accounts — admin-only.",
    "debug_access":     "Access debug logs and diagnostics — admin-only.",
    "memory_delete":    "Permanently delete memory entries.",
    "voice_record":     "Access microphone for voice input.",
    "camera_access":    "Access camera for vision features.",
    "location_access":  "Access device location.",
    "contacts_read":    "Read device contacts.",
    "calendar_read":    "Read calendar events.",
    "calendar_write":   "Create/modify calendar events.",
    "notification_send":"Send push notifications.",
}


@dataclass
class PermissionUsage:
    name: str
    total_uses: int = 0
    unique_users: Set[str] = field(default_factory=set)
    endpoints: Set[str] = field(default_factory=set)
    first_used: Optional[float] = None
    last_used: Optional[float] = None
    is_dangerous: bool = False
    granted_to: Set[str] = field(default_factory=set)
    denied_count: int = 0

    def record_use(self, user_id: str, endpoint: str = "") -> None:
        now = time.time()
        self.total_uses += 1
        self.unique_users.add(user_id)
        if endpoint:
            self.endpoints.add(endpoint)
        self.first_used = self.first_used or now
        self.last_used = now

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "total_uses": self.total_uses,
            "unique_user_count": len(self.unique_users),
            "endpoint_count": len(self.endpoints),
            "first_used": self.first_used,
            "last_used": self.last_used,
            "is_dangerous": self.is_dangerous,
            "granted_to": list(self.granted_to),
            "denied_count": self.denied_count,
        }


class PermissionAuditor:
    """
    Tracks and audits permission usage across the Aladdin backend.

    Usage::

        auditor = PermissionAuditor()
        auditor.record_use("read_memory", user_id="u1", endpoint="/api/chat")
        report  = auditor.generate_report()
        unused  = auditor.get_unused_permissions()
    """

    def __init__(self, alert_callback: Optional[Callable] = None) -> None:
        self._usage: Dict[str, PermissionUsage] = {}
        self._lock = threading.Lock()
        self._alert_callback = alert_callback
        self._revocation_candidates: Set[str] = set()
        self._auto_revoke_after_days: int = 90

    # ── Recording ─────────────────────────────────────────────────────────────

    def record_use(self, permission: str, user_id: str, endpoint: str = "") -> None:
        """Record a permission being successfully used."""
        with self._lock:
            if permission not in self._usage:
                self._usage[permission] = PermissionUsage(
                    name=permission, is_dangerous=permission in DANGEROUS_PERMISSIONS
                )
            self._usage[permission].record_use(user_id, endpoint)

        if permission in DANGEROUS_PERMISSIONS:
            log.warning("DANGEROUS PERM USED: '%s' user=%s endpoint=%s", permission, user_id, endpoint)
            if self._alert_callback:
                try:
                    self._alert_callback(permission, user_id, f"dangerous permission used (endpoint={endpoint})")
                except Exception as e:
                    log.warning("PermissionAuditor alert callback error: %s", e)

    def record_denial(self, permission: str, user_id: str) -> None:
        """Record a permission denial (attempted but rejected)."""
        with self._lock:
            if permission not in self._usage:
                self._usage[permission] = PermissionUsage(
                    name=permission, is_dangerous=permission in DANGEROUS_PERMISSIONS
                )
            self._usage[permission].denied_count += 1
        log.warning("PERM DENIED: '%s' user=%s", permission, user_id)

    def grant_permission(self, permission: str, role: str) -> None:
        """Record that a role has been granted a permission."""
        with self._lock:
            if permission not in self._usage:
                self._usage[permission] = PermissionUsage(
                    name=permission, is_dangerous=permission in DANGEROUS_PERMISSIONS
                )
            self._usage[permission].granted_to.add(role)
        log.info("Permission '%s' granted to role '%s'", permission, role)

    def recommend_revocation(self, permission: str) -> None:
        with self._lock:
            self._revocation_candidates.add(permission)
        log.info("Permission '%s' flagged for revocation review", permission)

    # ── Analysis ──────────────────────────────────────────────────────────────

    def get_unused_permissions(self, since_days: int = 30) -> List[str]:
        """Return permissions not used in the last N days."""
        cutoff = time.time() - since_days * 86400
        with self._lock:
            return [
                name for name, u in self._usage.items()
                if u.last_used is None or u.last_used < cutoff
            ]

    def get_dangerous_usage(self) -> List[PermissionUsage]:
        with self._lock:
            return [u for u in self._usage.values() if u.is_dangerous and u.total_uses > 0]

    def get_high_frequency(self, threshold: int = 1000) -> List[PermissionUsage]:
        with self._lock:
            return [u for u in self._usage.values() if u.total_uses >= threshold]

    def check_auto_revoke(self) -> List[str]:
        """Return permissions that qualify for auto-revocation (unused > threshold)."""
        candidates = self.get_unused_permissions(since_days=self._auto_revoke_after_days)
        for p in candidates:
            self.recommend_revocation(p)
        return candidates

    # ── Reports ───────────────────────────────────────────────────────────────

    def generate_report(self) -> dict:
        with self._lock:
            snapshot = {k: v for k, v in self._usage.items()}

        return {
            "generated_at": time.time(),
            "generated_at_iso": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "total_permissions_tracked": len(snapshot),
            "dangerous_permissions_used": [
                u.to_dict() for u in snapshot.values() if u.is_dangerous and u.total_uses > 0
            ],
            "unused_30d": self.get_unused_permissions(30),
            "unused_90d": self.get_unused_permissions(90),
            "revocation_candidates": list(self._revocation_candidates),
            "high_frequency": [
                {"name": u.name, "uses": u.total_uses}
                for u in self.get_high_frequency(500)
            ],
            "top_10": sorted(
                [{"name": u.name, "uses": u.total_uses} for u in snapshot.values()],
                key=lambda x: x["uses"], reverse=True
            )[:10],
            "denial_summary": sorted(
                [{"name": u.name, "denials": u.denied_count} for u in snapshot.values() if u.denied_count > 0],
                key=lambda x: x["denials"], reverse=True
            ),
        }

    def explain_permission(self, permission: str) -> str:
        """User-facing explanation of why a permission is needed."""
        return PERMISSION_EXPLANATIONS.get(permission, f"Required for '{permission}' operations.")

    def usage_for(self, permission: str) -> Optional[PermissionUsage]:
        with self._lock:
            return self._usage.get(permission)

    def reset_stats(self, permission: Optional[str] = None) -> None:
        with self._lock:
            targets = [permission] if permission else list(self._usage.keys())
            for p in targets:
                if p in self._usage:
                    u = self._usage[p]
                    u.total_uses = u.denied_count = 0
                    u.unique_users.clear()
                    u.endpoints.clear()
                    u.first_used = u.last_used = None
        log.info("PermissionAuditor: stats reset for %s", permission or "all")
