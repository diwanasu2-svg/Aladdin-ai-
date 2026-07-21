"""Project memory."""

from __future__ import annotations
import sqlite3, time, uuid, logging
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)

class ProjectsMemory:
    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT,
                status TEXT DEFAULT 'active', notes TEXT,
                created_at REAL, updated_at REAL)""")

    def _conn(self):
        c = sqlite3.connect(str(self._db)); c.row_factory = sqlite3.Row; return c
        c.execute("PRAGMA foreign_keys = ON")

    def create(self, name: str, description: Optional[str]=None, notes: Optional[str]=None) -> str:
        pid = str(uuid.uuid4()); now = time.time()
        with self._conn() as conn:
            conn.execute("INSERT INTO projects VALUES (?,?,?,?,?,?,?)", (pid,name,description,"active",notes,now,now))
        return pid

    def get(self, project_id: str) -> Optional[Dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM projects WHERE id=?", (project_id,)).fetchone()
        return dict(row) if row else None

    def list_all(self, status: Optional[str]=None) -> List[Dict]:
        with self._conn() as conn:
            if status:
                rows = conn.execute("SELECT * FROM projects WHERE status=? ORDER BY updated_at DESC", (status,)).fetchall()
            else:
                rows = conn.execute("SELECT * FROM projects ORDER BY updated_at DESC").fetchall()
        return [dict(r) for r in rows]

    def update(self, project_id: str, **fields) -> bool:
        allowed = {"name","description","status","notes"}
        fields = {k:v for k,v in fields.items() if k in allowed}
        if not fields: return False
        fields["updated_at"] = time.time()
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with self._conn() as conn:
            cur = conn.execute(f"UPDATE projects SET {set_clause} WHERE id=?", (*fields.values(), project_id))
        return cur.rowcount > 0

    def delete(self, project_id: str) -> bool:
        with self._conn() as conn:
            cur = conn.execute("DELETE FROM projects WHERE id=?", (project_id,))
        return cur.rowcount > 0
