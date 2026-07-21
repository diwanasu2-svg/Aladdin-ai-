"""CalendarStore — SQLite-backed calendar event CRUD with recurring support."""
from __future__ import annotations
import json
import logging
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

from .conflict_detector import find_conflicts, suggest_alternative
from .recurring import expand_recurrence

log = logging.getLogger(__name__)


class CalendarStore:
    def __init__(self, db_path: Path) -> None:
        self._db = db_path
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _conn(self):
        c = sqlite3.connect(str(self._db))
        c.execute("PRAGMA foreign_keys = ON")
        c.row_factory = sqlite3.Row
        return c

    def _init_db(self):
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT DEFAULT '',
                    location TEXT DEFAULT '',
                    start_ts REAL NOT NULL,
                    end_ts REAL,
                    all_day INTEGER DEFAULT 0,
                    recurrence_rule TEXT,
                    recurrence_interval INTEGER DEFAULT 1,
                    recurrence_end_ts REAL,
                    reminder_minutes INTEGER DEFAULT 15,
                    google_event_id TEXT,
                    deleted INTEGER DEFAULT 0,
                    created_at REAL,
                    updated_at REAL
                )""")

    def create(self, data: Dict[str, Any]) -> Dict:
        eid = str(uuid.uuid4())
        now = time.time()
        with self._conn() as conn:
            conn.execute("""
                INSERT INTO events (id,title,description,location,start_ts,end_ts,all_day,
                    recurrence_rule,recurrence_interval,recurrence_end_ts,reminder_minutes,
                    google_event_id,deleted,created_at,updated_at) VALUES
                    (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)""",
                (eid, data["title"], data.get("description",""), data.get("location",""),
                 data["start_ts"], data.get("end_ts", data["start_ts"]+3600),
                 1 if data.get("all_day") else 0,
                 data.get("recurrence_rule"), data.get("recurrence_interval", 1),
                 data.get("recurrence_end_ts"), data.get("reminder_minutes", 15),
                 data.get("google_event_id"), now, now))
        return self.get(eid)

    def get(self, event_id: str) -> Optional[Dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM events WHERE id=? AND deleted=0", (event_id,)).fetchone()
        return dict(row) if row else None

    def list(self, from_ts: Optional[float] = None, to_ts: Optional[float] = None,
             limit: int = 100, include_deleted: bool = False) -> List[Dict]:
        clauses, params = [], []
        if not include_deleted:
            clauses.append("deleted=0")
        if from_ts is not None:
            clauses.append("start_ts>=?"); params.append(from_ts)
        if to_ts is not None:
            clauses.append("start_ts<=?"); params.append(to_ts)
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        with self._conn() as conn:
            rows = conn.execute(f"SELECT * FROM events {where} ORDER BY start_ts LIMIT ?",
                                (*params, limit)).fetchall()
        return [dict(r) for r in rows]

    def update(self, event_id: str, data: Dict[str, Any]) -> Optional[Dict]:
        allowed = {"title","description","location","start_ts","end_ts","all_day",
                   "recurrence_rule","recurrence_interval","recurrence_end_ts","reminder_minutes"}
        fields = {k: v for k, v in data.items() if k in allowed}
        if not fields:
            return self.get(event_id)
        fields["updated_at"] = time.time()
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with self._conn() as conn:
            conn.execute(f"UPDATE events SET {set_clause} WHERE id=? AND deleted=0",
                         (*fields.values(), event_id))
        return self.get(event_id)

    def delete(self, event_id: str, soft: bool = True) -> bool:
        with self._conn() as conn:
            if soft:
                cur = conn.execute("UPDATE events SET deleted=1,updated_at=? WHERE id=?",
                                   (time.time(), event_id))
            else:
                cur = conn.execute("DELETE FROM events WHERE id=?", (event_id,))
        return cur.rowcount > 0

    def upsert_by_google_id(self, data: Dict) -> Dict:
        gid = data.get("google_event_id")
        if gid:
            with self._conn() as conn:
                row = conn.execute("SELECT id FROM events WHERE google_event_id=?", (gid,)).fetchone()
            if row:
                return self.update(row["id"], data) or data
        return self.create(data)

    def get_conflicts(self, event: Dict) -> List[Dict]:
        events = self.list(from_ts=event.get("start_ts", 0) - 86400,
                           to_ts=event.get("end_ts", 0) + 86400)
        pairs = find_conflicts([event] + events)
        conflicting = []
        for a, b in pairs:
            other = b if a.get("id") == event.get("id") else a
            if other.get("id") != event.get("id"):
                conflicting.append(other)
        return conflicting

    def get_occurrences(self, event_id: str, count: int = 10) -> List[Dict]:
        ev = self.get(event_id)
        if not ev:
            return []
        return expand_recurrence(ev, count)
