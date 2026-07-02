"""User preferences memory."""

from __future__ import annotations
import json, sqlite3, time, logging
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)

class PreferencesMemory:
    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS preferences (
                key TEXT PRIMARY KEY, value TEXT NOT NULL,
                category TEXT DEFAULT 'general', updated_at REAL)""")

    def _conn(self):
        c = sqlite3.connect(str(self._db)); c.row_factory = sqlite3.Row; return c
        c.execute("PRAGMA foreign_keys = ON")

    def get(self, key: str) -> Optional[Any]:
        with self._conn() as conn:
            row = conn.execute("SELECT value FROM preferences WHERE key=?", (key,)).fetchone()
        if not row: return None
        try: return json.loads(row["value"])
        except Exception: return row["value"]

    def set(self, key: str, value: Any, category: str = "general") -> None:
        with self._conn() as conn:
            conn.execute("INSERT OR REPLACE INTO preferences (key,value,category,updated_at) VALUES (?,?,?,?)",
                         (key, json.dumps(value), category, time.time()))

    def list_all(self, category: Optional[str] = None) -> List[Dict]:
        with self._conn() as conn:
            if category:
                rows = conn.execute("SELECT * FROM preferences WHERE category=?", (category,)).fetchall()
            else:
                rows = conn.execute("SELECT * FROM preferences").fetchall()
        result = []
        for r in rows:
            d = dict(r)
            try: d["value"] = json.loads(d["value"])
            except Exception: pass
            result.append(d)
        return result

    def delete(self, key: str) -> bool:
        with self._conn() as conn:
            cur = conn.execute("DELETE FROM preferences WHERE key=?", (key,))
        return cur.rowcount > 0
