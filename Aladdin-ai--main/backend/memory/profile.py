"""User profile memory."""

from __future__ import annotations
import json, sqlite3, time, logging
from pathlib import Path
from typing import Any, Dict, Optional

log = logging.getLogger(__name__)

class ProfileMemory:
    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS profile (
                key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at REAL)""")

    def _conn(self):
        c = sqlite3.connect(str(self._db)); c.row_factory = sqlite3.Row; return c
        c.execute("PRAGMA foreign_keys = ON")

    def get(self) -> Dict[str, Any]:
        with self._conn() as conn:
            rows = conn.execute("SELECT key, value FROM profile").fetchall()
        result = {}
        for r in rows:
            try: result[r["key"]] = json.loads(r["value"])
            except Exception: result[r["key"]] = r["value"]
        return result

    def update(self, data: Dict[str, Any]) -> None:
        now = time.time()
        with self._conn() as conn:
            for k, v in data.items():
                conn.execute("INSERT OR REPLACE INTO profile (key,value,updated_at) VALUES (?,?,?)",
                             (k, json.dumps(v), now))

    def set_field(self, key: str, value: Any) -> None:
        with self._conn() as conn:
            conn.execute("INSERT OR REPLACE INTO profile (key,value,updated_at) VALUES (?,?,?)",
                         (key, json.dumps(value), time.time()))
