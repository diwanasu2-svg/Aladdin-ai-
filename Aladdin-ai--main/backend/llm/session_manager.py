"""
Session management — Task 20: SQLite persistence + crash recovery + startup restore.
"""
from __future__ import annotations

import json
import logging
import os
import sqlite3
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)

_DB_PATH = Path(os.getenv("SESSIONS_DB", "data/sessions.sqlite"))


@dataclass
class Session:
    session_id: str
    messages: List[Dict[str, str]] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    last_active: float = field(default_factory=time.time)
    model: Optional[str] = None
    provider: Optional[str] = None
    metadata: Dict = field(default_factory=dict)

    def touch(self):
        self.last_active = time.time()

    @property
    def message_count(self) -> int:
        return len(self.messages)


def _get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(str(_DB_PATH))
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    return conn


def _init_db():
    try:
        _DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        with _get_conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    messages   TEXT NOT NULL DEFAULT '[]',
                    created_at REAL NOT NULL,
                    last_active REAL NOT NULL,
                    model      TEXT,
                    provider   TEXT,
                    metadata   TEXT NOT NULL DEFAULT '{}'
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON sessions(last_active)")
            conn.commit()
        log.info("Session DB initialized at %s", _DB_PATH)
    except Exception as exc:
        log.warning("Session DB init failed — sessions will not persist: %s", exc)


_init_db()


class SessionManager:
    """SQLite-backed session store with in-memory cache and crash recovery (Task 20)."""

    def __init__(self, timeout_seconds: int = 3600) -> None:
        self._sessions: Dict[str, Session] = {}
        self._timeout = timeout_seconds
        self._restore_from_db()

    def _restore_from_db(self):
        """Task 20: Restore active sessions from DB on startup (crash recovery)."""
        try:
            cutoff = time.time() - self._timeout
            with _get_conn() as conn:
                rows = conn.execute(
                    "SELECT * FROM sessions WHERE last_active > ?", (cutoff,)
                ).fetchall()
            restored = 0
            for row in rows:
                try:
                    s = Session(
                        session_id=row["session_id"],
                        messages=json.loads(row["messages"]),
                        created_at=row["created_at"],
                        last_active=row["last_active"],
                        model=row["model"],
                        provider=row["provider"],
                        metadata=json.loads(row["metadata"]),
                    )
                    self._sessions[s.session_id] = s
                    restored += 1
                except Exception as exc:
                    log.warning("Session restore failed for id=%s: %s", row["session_id"], exc)
            log.info("Session recovery: restored %d sessions from DB", restored)
        except Exception as exc:
            log.warning("Session restore from DB failed: %s", exc)

    def _persist(self, session: Session):
        try:
            with _get_conn() as conn:
                conn.execute(
                    """INSERT OR REPLACE INTO sessions
                       (session_id, messages, created_at, last_active, model, provider, metadata)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    (
                        session.session_id,
                        json.dumps(session.messages),
                        session.created_at,
                        session.last_active,
                        session.model,
                        session.provider,
                        json.dumps(session.metadata),
                    ),
                )
                conn.commit()
        except Exception as exc:
            log.debug("Session persist failed id=%s: %s", session.session_id, exc)

    def _delete_from_db(self, session_id: str):
        try:
            with _get_conn() as conn:
                conn.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
                conn.commit()
        except Exception as exc:
            log.debug("Session DB delete failed id=%s: %s", session_id, exc)

    def create_session(
        self,
        session_id: Optional[str] = None,
        model: Optional[str] = None,
        provider: Optional[str] = None,
    ) -> Session:
        sid = session_id or str(uuid.uuid4())
        session = Session(session_id=sid, model=model, provider=provider)
        self._sessions[sid] = session
        self._persist(session)
        log.info("Created session %s", sid)
        return session

    def get_session(self, session_id: str) -> Optional[Session]:
        session = self._sessions.get(session_id)
        if session is None:
            return None
        if time.time() - session.last_active > self._timeout:
            self.delete_session(session_id)
            log.info("Session %s expired", session_id)
            return None
        session.touch()
        self._persist(session)
        return session

    def get_or_create(
        self,
        session_id: Optional[str],
        model: Optional[str] = None,
        provider: Optional[str] = None,
    ) -> Session:
        if session_id:
            s = self.get_session(session_id)
            if s:
                return s
        return self.create_session(session_id, model, provider)

    def delete_session(self, session_id: str) -> bool:
        existed = self._sessions.pop(session_id, None) is not None
        self._delete_from_db(session_id)
        return existed

    def add_message(self, session_id: str, role: str, content: str) -> None:
        session = self.get_session(session_id)
        if session:
            session.messages.append({"role": role, "content": content})
            session.touch()
            self._persist(session)

    def list_sessions(self) -> List[Dict]:
        now = time.time()
        active = [s for s in self._sessions.values() if now - s.last_active <= self._timeout]
        return [
            {
                "session_id": s.session_id,
                "created_at": s.created_at,
                "last_active": s.last_active,
                "message_count": s.message_count,
                "model": s.model,
                "provider": s.provider,
            }
            for s in active
        ]

    def evict_expired(self) -> int:
        now = time.time()
        expired = [sid for sid, s in self._sessions.items() if now - s.last_active > self._timeout]
        for sid in expired:
            del self._sessions[sid]
            self._delete_from_db(sid)
        if expired:
            log.info("Evicted %d expired sessions", len(expired))
        return len(expired)


session_manager = SessionManager()
