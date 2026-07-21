"""Long-term persistent memory with backup/restore."""

from __future__ import annotations

import json
import shutil
import sqlite3
import time
import uuid
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class LongTermMemory:
    """Key-value long-term store backed by SQLite."""

    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _conn(self):
        conn = sqlite3.connect(str(self._db))
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS long_term (
                    id TEXT PRIMARY KEY,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    category TEXT DEFAULT 'general',
                    tags TEXT DEFAULT '[]',
                    timestamp REAL NOT NULL
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_lt_key ON long_term(key)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_lt_cat ON long_term(category)")

    def save(self, key: str, value: str, category: str = "general", tags: Optional[List[str]] = None) -> str:
        rid = str(uuid.uuid4())
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO long_term (id,key,value,category,tags,timestamp) VALUES (?,?,?,?,?,?)",
                (rid, key, value, category, json.dumps(tags or []), time.time()),
            )
        return rid

    def get(self, key: str) -> Optional[Dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM long_term WHERE key=? ORDER BY timestamp DESC LIMIT 1", (key,)).fetchone()
        if not row:
            return None
        d = dict(row)
        d["tags"] = json.loads(d.get("tags", "[]"))
        return d

    def list_all(self, category: Optional[str] = None) -> List[Dict]:
        with self._conn() as conn:
            if category:
                rows = conn.execute("SELECT * FROM long_term WHERE category=? ORDER BY timestamp DESC", (category,)).fetchall()
            else:
                rows = conn.execute("SELECT * FROM long_term ORDER BY timestamp DESC").fetchall()
        result = []
        for r in rows:
            d = dict(r)
            d["tags"] = json.loads(d.get("tags", "[]"))
            result.append(d)
        return result

    def delete(self, key: str) -> bool:
        with self._conn() as conn:
            cur = conn.execute("DELETE FROM long_term WHERE key=?", (key,))
        return cur.rowcount > 0

    def backup(self, backup_path: Path) -> str:
        backup_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(str(self._db), str(backup_path))
        log.info("Backed up long-term memory to %s", backup_path)
        return str(backup_path)

    def restore(self, backup_path: Path) -> bool:
        if not backup_path.exists():
            return False
        shutil.copy2(str(backup_path), str(self._db))
        log.info("Restored long-term memory from %s", backup_path)
        return True
