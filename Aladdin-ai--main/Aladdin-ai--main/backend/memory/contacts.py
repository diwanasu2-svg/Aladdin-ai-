"""Contacts CRUD memory."""

from __future__ import annotations
import json, sqlite3, time, uuid, logging
from pathlib import Path
from typing import Dict, List, Optional

log = logging.getLogger(__name__)

class ContactsMemory:
    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        self._db.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS contacts (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT, email TEXT,
                relation TEXT, notes TEXT, created_at REAL, updated_at REAL)""")

    def _conn(self):
        c = sqlite3.connect(str(self._db)); c.row_factory = sqlite3.Row; return c
        c.execute("PRAGMA foreign_keys = ON")

    def add(self, name: str, phone: Optional[str]=None, email: Optional[str]=None,
            relation: Optional[str]=None, notes: Optional[str]=None) -> str:
        cid = str(uuid.uuid4()); now = time.time()
        with self._conn() as conn:
            conn.execute("INSERT INTO contacts VALUES (?,?,?,?,?,?,?,?)", (cid,name,phone,email,relation,notes,now,now))
        return cid

    def get(self, contact_id: str) -> Optional[Dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM contacts WHERE id=?", (contact_id,)).fetchone()
        return dict(row) if row else None

    def list_all(self) -> List[Dict]:
        with self._conn() as conn:
            return [dict(r) for r in conn.execute("SELECT * FROM contacts ORDER BY name").fetchall()]

    def update(self, contact_id: str, **fields) -> bool:
        allowed = {"name","phone","email","relation","notes"}
        fields = {k:v for k,v in fields.items() if k in allowed}
        if not fields: return False
        fields["updated_at"] = time.time()
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with self._conn() as conn:
            cur = conn.execute(f"UPDATE contacts SET {set_clause} WHERE id=?", (*fields.values(), contact_id))
        return cur.rowcount > 0

    def delete(self, contact_id: str) -> bool:
        with self._conn() as conn:
            cur = conn.execute("DELETE FROM contacts WHERE id=?", (contact_id,))
        return cur.rowcount > 0
