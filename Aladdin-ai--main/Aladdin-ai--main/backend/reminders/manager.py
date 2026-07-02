"""ReminderManager — SQLite-backed reminders CRUD with snooze and recurrence."""
from __future__ import annotations
import logging
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

from .snooze import snooze as apply_snooze

log = logging.getLogger(__name__)


class ReminderManager:
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
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    body TEXT DEFAULT '',
                    remind_at REAL,
                    priority INTEGER DEFAULT 0,
                    sound_id TEXT DEFAULT 'default',
                    volume REAL DEFAULT 0.8,
                    done INTEGER DEFAULT 0,
                    deleted INTEGER DEFAULT 0,
                    notified INTEGER DEFAULT 0,
                    snoozed INTEGER DEFAULT 0,
                    snooze_count INTEGER DEFAULT 0,
                    snooze_duration TEXT,
                    recurrence_rule TEXT,
                    recurrence_interval INTEGER DEFAULT 1,
                    recurrence_end_ts REAL,
                    created_at REAL,
                    updated_at REAL
                )""")

    def create(self, data: Dict[str, Any]) -> Dict:
        rid = str(uuid.uuid4())
        now = time.time()
        with self._conn() as conn:
            conn.execute("""
                INSERT INTO reminders (id,title,body,remind_at,priority,sound_id,volume,
                    done,deleted,notified,snoozed,snooze_count,
                    recurrence_rule,recurrence_interval,recurrence_end_ts,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,0,0,0,0,0,?,?,?,?,?)""",
                (rid, data["title"], data.get("body",""), data.get("remind_at"),
                 data.get("priority", 0), data.get("sound_id","default"),
                 data.get("volume", 0.8),
                 data.get("recurrence_rule"), data.get("recurrence_interval", 1),
                 data.get("recurrence_end_ts"), now, now))
        return self.get(rid)

    def get(self, reminder_id: str) -> Optional[Dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM reminders WHERE id=? AND deleted=0",
                               (reminder_id,)).fetchone()
        return dict(row) if row else None

    def list(self, include_done: bool = False, include_deleted: bool = False) -> List[Dict]:
        clauses = []
        if not include_deleted:
            clauses.append("deleted=0")
        if not include_done:
            clauses.append("done=0")
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        with self._conn() as conn:
            rows = conn.execute(f"SELECT * FROM reminders {where} ORDER BY remind_at ASC NULLS LAST").fetchall()
        return [dict(r) for r in rows]

    def update(self, reminder_id: str, data: Dict[str, Any]) -> Optional[Dict]:
        allowed = {"title","body","remind_at","priority","sound_id","volume",
                   "done","recurrence_rule","recurrence_interval","recurrence_end_ts","snoozed","snooze_count","snooze_duration"}
        fields = {k: (1 if v is True else 0 if v is False else v) for k, v in data.items() if k in allowed}
        if not fields:
            return self.get(reminder_id)
        fields["updated_at"] = time.time()
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with self._conn() as conn:
            conn.execute(f"UPDATE reminders SET {set_clause} WHERE id=? AND deleted=0",
                         (*fields.values(), reminder_id))
        return self.get(reminder_id)

    def delete(self, reminder_id: str, soft: bool = True) -> bool:
        with self._conn() as conn:
            if soft:
                cur = conn.execute("UPDATE reminders SET deleted=1,updated_at=? WHERE id=?",
                                   (time.time(), reminder_id))
            else:
                cur = conn.execute("DELETE FROM reminders WHERE id=?", (reminder_id,))
        return cur.rowcount > 0

    def snooze(self, reminder_id: str, duration: str = "10min") -> Optional[Dict]:
        rem = self.get(reminder_id)
        if not rem:
            return None
        updated = apply_snooze(rem, duration)
        return self.update(reminder_id, updated)

    def mark_notified(self, reminder_id: str) -> None:
        with self._conn() as conn:
            conn.execute("UPDATE reminders SET notified=1,updated_at=? WHERE id=?",
                         (time.time(), reminder_id))
        # Auto-advance recurring reminder
        rem = self.get(reminder_id)
        if rem and rem.get("recurrence_rule") and rem.get("remind_at"):
            from ..calendar.recurring import next_occurrence
            nxt = next_occurrence(rem["remind_at"], rem["recurrence_rule"],
                                  rem.get("recurrence_interval", 1))
            if nxt:
                end_ts = rem.get("recurrence_end_ts")
                if not end_ts or nxt <= end_ts:
                    self.update(reminder_id, {"remind_at": nxt, "notified": 0, "done": 0})
                    return
        self.update(reminder_id, {"done": 1})

    def get_due(self, before_ts: Optional[float] = None) -> List[Dict]:
        ts = before_ts or time.time()
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT * FROM reminders WHERE remind_at<=? AND done=0 AND deleted=0 AND notified=0",
                (ts,)).fetchall()
        return [dict(r) for r in rows]
