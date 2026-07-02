"""Location memory."""

from __future__ import annotations
import sqlite3, time, uuid, logging
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)

class LocationsMemory:
    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS locations (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, address TEXT,
                latitude REAL, longitude REAL, notes TEXT, category TEXT,
                created_at REAL)""")

    def _conn(self):
        c = sqlite3.connect(str(self._db)); c.row_factory = sqlite3.Row; return c
        c.execute("PRAGMA foreign_keys = ON")

    def add(self, name: str, address: Optional[str]=None, latitude: Optional[float]=None,
            longitude: Optional[float]=None, notes: Optional[str]=None, category: Optional[str]=None) -> str:
        lid = str(uuid.uuid4())
        with self._conn() as conn:
            conn.execute("INSERT INTO locations VALUES (?,?,?,?,?,?,?,?)",
                         (lid, name, address, latitude, longitude, notes, category, time.time()))
        return lid

    def get(self, location_id: str) -> Optional[Dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM locations WHERE id=?", (location_id,)).fetchone()
        return dict(row) if row else None

    def list_all(self, category: Optional[str]=None) -> List[Dict]:
        with self._conn() as conn:
            if category:
                rows = conn.execute("SELECT * FROM locations WHERE category=? ORDER BY name", (category,)).fetchall()
            else:
                rows = conn.execute("SELECT * FROM locations ORDER BY name").fetchall()
        return [dict(r) for r in rows]

    def delete(self, location_id: str) -> bool:
        with self._conn() as conn:
            cur = conn.execute("DELETE FROM locations WHERE id=?", (location_id,))
        return cur.rowcount > 0
