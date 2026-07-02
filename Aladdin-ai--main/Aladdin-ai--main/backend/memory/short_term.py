"""Short-term (session) memory with auto-saving and summarization."""

from __future__ import annotations

import json
import sqlite3
import time
import uuid
import logging
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)


class ShortTermMemory:
    """Stores conversation turns per session in SQLite."""

    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self._db))
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self) -> None:
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS short_term (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp REAL NOT NULL,
                    language TEXT
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_st_session ON short_term(session_id)")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS summaries (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    created_at REAL NOT NULL
                )
            """)

    def save(self, session_id: str, role: str, content: str, language: Optional[str] = None) -> str:
        rid = str(uuid.uuid4())
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO short_term (id, session_id, role, content, timestamp, language) VALUES (?,?,?,?,?,?)",
                (rid, session_id, role, content, time.time(), language),
            )
        return rid

    def get(self, session_id: str, limit: int = 50) -> List[Dict]:
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT * FROM short_term WHERE session_id=? ORDER BY timestamp ASC LIMIT ?",
                (session_id, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def get_as_messages(self, session_id: str, limit: int = 50) -> List[Dict[str, str]]:
        records = self.get(session_id, limit)
        return [{"role": r["role"], "content": r["content"]} for r in records]

    def save_summary(self, session_id: str, summary: str) -> str:
        sid = str(uuid.uuid4())
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO summaries (id, session_id, summary, created_at) VALUES (?,?,?,?)",
                (sid, session_id, summary, time.time()),
            )
        return sid

    def get_summary(self, session_id: str) -> Optional[str]:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT summary FROM summaries WHERE session_id=? ORDER BY created_at DESC LIMIT 1",
                (session_id,),
            ).fetchone()
        return row["summary"] if row else None

    def inject_into_prompt(self, session_id: str, system_prompt: str) -> str:
        summary = self.get_summary(session_id)
        if summary:
            return f"{system_prompt}\n\n[Previous conversation summary: {summary}]"
        return system_prompt

    def count(self, session_id: str) -> int:
        with self._conn() as conn:
            row = conn.execute("SELECT COUNT(*) as c FROM short_term WHERE session_id=?", (session_id,)).fetchone()
        return row["c"]
