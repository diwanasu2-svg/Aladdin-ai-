"""
Task 12 — SecurityManager: centralized singleton for rate-limit persistence,
config caching, IP allow/block list, and threat detection.
"""
from __future__ import annotations

import logging
import os
import sqlite3
import time
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Optional, Set

logger = logging.getLogger(__name__)

_DB_PATH = Path(os.getenv("SECURITY_DB", "data/security.sqlite"))


class SecurityManager:
    """
    Singleton security orchestrator.

    • Rate-limit persistence: delegates to RateLimiter (DB-backed)
    • Config cache: in-process LRU for security settings
    • IP allow/block list: SQLite-backed with expiry
    • Threat detection: counts suspicious events per IP/user
    """

    _instance: Optional["SecurityManager"] = None

    def __new__(cls) -> "SecurityManager":
        if cls._instance is None:
            obj = super().__new__(cls)
            obj._initialized = False
            cls._instance = obj
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self._blocked_ips: Set[str] = set()
        self._allowed_ips: Set[str] = set()
        self._threat_counters: Dict[str, int] = {}
        self._init_db()
        self._load_from_db()
        logger.info("SecurityManager initialized")

    def _init_db(self):
        try:
            _DB_PATH.parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(str(_DB_PATH)) as conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS blocked_ips (
                        ip TEXT PRIMARY KEY,
                        reason TEXT,
                        blocked_at REAL NOT NULL,
                        expires_at REAL
                    )
                """)
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS allowed_ips (
                        ip TEXT PRIMARY KEY,
                        reason TEXT,
                        added_at REAL NOT NULL
                    )
                """)
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS security_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_type TEXT NOT NULL,
                        ip TEXT,
                        user_id TEXT,
                        detail TEXT,
                        timestamp REAL NOT NULL
                    )
                """)
                conn.execute("CREATE INDEX IF NOT EXISTS idx_sec_events_ts ON security_events(timestamp)")
                conn.commit()
        except Exception as exc:
            logger.error("SecurityManager DB init failed: %s", exc)

    def _load_from_db(self):
        try:
            now = time.time()
            with sqlite3.connect(str(_DB_PATH)) as conn:
                rows = conn.execute(
                    "SELECT ip FROM blocked_ips WHERE expires_at IS NULL OR expires_at > ?", (now,)
                ).fetchall()
                self._blocked_ips = {r[0] for r in rows}
                rows = conn.execute("SELECT ip FROM allowed_ips").fetchall()
                self._allowed_ips = {r[0] for r in rows}
            logger.info("Loaded %d blocked IPs, %d allowed IPs", len(self._blocked_ips), len(self._allowed_ips))
        except Exception as exc:
            logger.warning("SecurityManager DB load failed: %s", exc)

    # ── IP block/allow ────────────────────────────────────────────────────────

    def block_ip(self, ip: str, reason: str = "", duration_seconds: Optional[int] = None):
        self._blocked_ips.add(ip)
        expires = time.time() + duration_seconds if duration_seconds else None
        try:
            with sqlite3.connect(str(_DB_PATH)) as conn:
                conn.execute(
                    "INSERT OR REPLACE INTO blocked_ips (ip, reason, blocked_at, expires_at) VALUES (?,?,?,?)",
                    (ip, reason, time.time(), expires),
                )
                conn.commit()
            logger.warning("Blocked IP: %s reason=%s expires=%s", ip, reason, expires)
        except Exception as exc:
            logger.error("block_ip DB error: %s", exc)

    def unblock_ip(self, ip: str):
        self._blocked_ips.discard(ip)
        try:
            with sqlite3.connect(str(_DB_PATH)) as conn:
                conn.execute("DELETE FROM blocked_ips WHERE ip = ?", (ip,))
                conn.commit()
        except Exception as exc:
            logger.error("unblock_ip DB error: %s", exc)

    def allow_ip(self, ip: str, reason: str = ""):
        self._allowed_ips.add(ip)
        try:
            with sqlite3.connect(str(_DB_PATH)) as conn:
                conn.execute(
                    "INSERT OR REPLACE INTO allowed_ips (ip, reason, added_at) VALUES (?,?,?)",
                    (ip, reason, time.time()),
                )
                conn.commit()
        except Exception as exc:
            logger.error("allow_ip DB error: %s", exc)

    def is_blocked(self, ip: str) -> bool:
        if ip in self._allowed_ips:
            return False
        return ip in self._blocked_ips

    def is_allowed(self, ip: str) -> bool:
        return ip in self._allowed_ips

    # ── Threat detection ──────────────────────────────────────────────────────

    def record_threat_event(
        self,
        event_type: str,
        ip: str = "",
        user_id: str = "",
        detail: str = "",
        auto_block_threshold: int = 10,
        block_duration_seconds: int = 3600,
    ):
        key = ip or user_id or "unknown"
        self._threat_counters[key] = self._threat_counters.get(key, 0) + 1
        try:
            with sqlite3.connect(str(_DB_PATH)) as conn:
                conn.execute(
                    "INSERT INTO security_events (event_type, ip, user_id, detail, timestamp) VALUES (?,?,?,?,?)",
                    (event_type, ip, user_id, detail, time.time()),
                )
                conn.commit()
        except Exception as exc:
            logger.debug("record_threat_event DB error: %s", exc)

        count = self._threat_counters[key]
        if count >= auto_block_threshold and ip and not self.is_blocked(ip):
            self.block_ip(ip, f"Auto-blocked: {event_type} x{count}", block_duration_seconds)

        logger.warning(
            "Security event: type=%s ip=%s user=%s count=%d detail=%s",
            event_type, ip, user_id, count, detail,
        )

    def reset_threat_counter(self, key: str):
        self._threat_counters.pop(key, None)

    def get_threat_count(self, key: str) -> int:
        return self._threat_counters.get(key, 0)

    # ── Config cache ──────────────────────────────────────────────────────────

    @lru_cache(maxsize=256)
    def get_setting(self, key: str, default: str = "") -> str:
        """Cached security setting retrieval."""
        return os.getenv(key, default)

    def invalidate_config_cache(self):
        self.get_setting.cache_clear()
        logger.info("SecurityManager config cache cleared")

    # ── Cleanup ───────────────────────────────────────────────────────────────

    def cleanup_expired(self):
        now = time.time()
        try:
            with sqlite3.connect(str(_DB_PATH)) as conn:
                expired = conn.execute(
                    "SELECT ip FROM blocked_ips WHERE expires_at IS NOT NULL AND expires_at < ?", (now,)
                ).fetchall()
                for (ip,) in expired:
                    self._blocked_ips.discard(ip)
                conn.execute("DELETE FROM blocked_ips WHERE expires_at IS NOT NULL AND expires_at < ?", (now,))
                # Prune security_events older than 30 days
                cutoff = now - 30 * 86400
                conn.execute("DELETE FROM security_events WHERE timestamp < ?", (cutoff,))
                conn.commit()
            if expired:
                logger.info("Unblocked %d expired IPs", len(expired))
        except Exception as exc:
            logger.warning("SecurityManager cleanup failed: %s", exc)

    def get_stats(self) -> dict:
        return {
            "blocked_ips": len(self._blocked_ips),
            "allowed_ips": len(self._allowed_ips),
            "tracked_threats": len(self._threat_counters),
        }


security_manager = SecurityManager()
