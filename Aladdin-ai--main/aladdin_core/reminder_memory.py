"""Reminder Memory — persistent reminder store with recurrence support.

Part of Phase 3 — Smart Memory Part 2.

This is the *memory layer* for reminders (long-term storage and queries). The
existing ``ReminderCfg`` / runtime reminder-checking service is preserved and
can be wired to read/write through this manager.
"""

from __future__ import annotations

import logging
import sqlite3
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


VALID_RECURRENCE = {"none", "daily", "weekly", "monthly", "yearly"}


def _parse_dt(value: Any) -> Optional[datetime]:
    """Best-effort datetime parser."""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(float(value))
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value)
        except ValueError:
            for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
                try:
                    return datetime.strptime(value, fmt)
                except ValueError:
                    continue
    return None


class ReminderMemory:
    """Persistent reminder store.

    Schema:
      reminders: id, title, description, due_at, completed, completed_at,
                 recurrence ('none'|'daily'|'weekly'|'monthly'|'yearly'),
                 recurrence_interval (int), tags, priority, created_at,
                 updated_at
    """

    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS reminders (
                id                  TEXT PRIMARY KEY,
                title               TEXT NOT NULL,
                description         TEXT,
                due_at              DATETIME,
                completed           INTEGER DEFAULT 0,
                completed_at        DATETIME,
                recurrence          TEXT DEFAULT 'none',
                recurrence_interval INTEGER DEFAULT 1,
                tags                TEXT,
                priority            INTEGER DEFAULT 1,
                created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS reminders_due_idx ON reminders(due_at)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS reminders_completed_idx "
            "ON reminders(completed)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # CRUD
    # ------------------------------------------------------------------
    def add_reminder(
        self,
        title: str,
        due_at: Any = None,
        description: Optional[str] = None,
        recurrence: str = "none",
        recurrence_interval: int = 1,
        tags: Optional[str] = None,
        priority: int = 1,
    ) -> str:
        if recurrence not in VALID_RECURRENCE:
            recurrence = "none"
        due = _parse_dt(due_at)
        rid = str(uuid.uuid4())
        self._db.execute(
            "INSERT INTO reminders(id, title, description, due_at, "
            "recurrence, recurrence_interval, tags, priority) "
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            (
                rid,
                title,
                description,
                due.isoformat() if due else None,
                recurrence,
                int(recurrence_interval),
                tags,
                int(priority),
            ),
        )
        self._db.commit()
        log.info("Reminder added: %s (%s)", title, rid)
        return rid

    def get(self, reminder_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM reminders WHERE id = ?", (reminder_id,)
        ).fetchone()
        return dict(row) if row else None

    def update_reminder(self, reminder_id: str, **fields: Any) -> bool:
        if not self.get(reminder_id):
            return False
        allowed = {
            "title",
            "description",
            "due_at",
            "recurrence",
            "recurrence_interval",
            "tags",
            "priority",
        }
        updates: List[str] = []
        values: List[Any] = []
        for k, v in fields.items():
            if k not in allowed:
                continue
            if k == "due_at":
                dt = _parse_dt(v)
                v = dt.isoformat() if dt else None
            if k == "recurrence" and v not in VALID_RECURRENCE:
                continue
            updates.append(f"{k} = ?")
            values.append(v)
        if not updates:
            return False
        updates.append("updated_at = CURRENT_TIMESTAMP")
        values.append(reminder_id)
        self._db.execute(
            f"UPDATE reminders SET {', '.join(updates)} WHERE id = ?",
            values,
        )
        self._db.commit()
        return True

    def delete_reminder(self, reminder_id: str) -> bool:
        if not self.get(reminder_id):
            return False
        self._db.execute("DELETE FROM reminders WHERE id = ?", (reminder_id,))
        self._db.commit()
        log.info("Reminder deleted: %s", reminder_id)
        return True

    # ------------------------------------------------------------------
    # Completion / recurrence
    # ------------------------------------------------------------------
    @staticmethod
    def _next_due(
        current: datetime, recurrence: str, interval: int
    ) -> Optional[datetime]:
        interval = max(int(interval or 1), 1)
        if recurrence == "daily":
            return current + timedelta(days=interval)
        if recurrence == "weekly":
            return current + timedelta(weeks=interval)
        if recurrence == "monthly":
            # naive: +30 days * interval to avoid calendar math
            return current + timedelta(days=30 * interval)
        if recurrence == "yearly":
            try:
                return current.replace(year=current.year + interval)
            except ValueError:
                # Feb 29 etc.
                return current + timedelta(days=365 * interval)
        return None

    def mark_completed(self, reminder_id: str) -> bool:
        """Mark a reminder completed. If it is recurring, schedule the next
        occurrence by rolling ``due_at`` forward and clearing completion."""
        rem = self.get(reminder_id)
        if not rem:
            return False
        recurrence = rem.get("recurrence", "none")
        if recurrence != "none" and rem.get("due_at"):
            cur = _parse_dt(rem["due_at"]) or datetime.now()
            nxt = self._next_due(cur, recurrence, rem.get("recurrence_interval", 1))
            if nxt:
                self._db.execute(
                    "UPDATE reminders SET due_at = ?, completed = 0, "
                    "completed_at = NULL, updated_at = CURRENT_TIMESTAMP "
                    "WHERE id = ?",
                    (nxt.isoformat(), reminder_id),
                )
                self._db.commit()
                return True

        self._db.execute(
            "UPDATE reminders SET completed = 1, "
            "completed_at = CURRENT_TIMESTAMP, "
            "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            (reminder_id,),
        )
        self._db.commit()
        return True

    def reopen(self, reminder_id: str) -> bool:
        if not self.get(reminder_id):
            return False
        self._db.execute(
            "UPDATE reminders SET completed = 0, completed_at = NULL, "
            "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            (reminder_id,),
        )
        self._db.commit()
        return True

    # ------------------------------------------------------------------
    # Queries
    # ------------------------------------------------------------------
    def list_pending(self) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM reminders WHERE completed = 0 "
            "ORDER BY (due_at IS NULL), due_at ASC"
        ).fetchall()
        return [dict(r) for r in rows]

    def list_completed(self) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM reminders WHERE completed = 1 " "ORDER BY completed_at DESC"
        ).fetchall()
        return [dict(r) for r in rows]

    def list_all(self) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM reminders ORDER BY "
            "(due_at IS NULL), due_at ASC, created_at ASC"
        ).fetchall()
        return [dict(r) for r in rows]

    def due_within(self, seconds: int) -> List[Dict[str, Any]]:
        """Reminders whose due_at is now..now+seconds and not yet completed."""
        now = datetime.now()
        upto = now + timedelta(seconds=seconds)
        rows = self._db.execute(
            "SELECT * FROM reminders WHERE completed = 0 AND due_at IS NOT NULL "
            "AND due_at <= ? ORDER BY due_at ASC",
            (upto.isoformat(),),
        ).fetchall()
        result: List[Dict[str, Any]] = []
        for r in rows:
            due = _parse_dt(r["due_at"])
            if due and due >= now - timedelta(seconds=seconds):
                result.append(dict(r))
        return result

    def overdue(self) -> List[Dict[str, Any]]:
        now = datetime.now().isoformat()
        rows = self._db.execute(
            "SELECT * FROM reminders WHERE completed = 0 AND due_at IS NOT NULL "
            "AND due_at < ? ORDER BY due_at ASC",
            (now,),
        ).fetchall()
        return [dict(r) for r in rows]

    def search(self, query: str) -> List[Dict[str, Any]]:
        like = f"%{query.lower()}%"
        rows = self._db.execute(
            "SELECT * FROM reminders WHERE LOWER(title) LIKE ? "
            "OR LOWER(IFNULL(description,'')) LIKE ? "
            "ORDER BY (due_at IS NULL), due_at ASC",
            (like, like),
        ).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------
    # Stats / utility
    # ------------------------------------------------------------------
    def stats(self) -> Dict[str, Any]:
        total = self._db.execute("SELECT COUNT(*) AS c FROM reminders").fetchone()["c"]
        completed = self._db.execute(
            "SELECT COUNT(*) AS c FROM reminders WHERE completed = 1"
        ).fetchone()["c"]
        return {
            "total": total,
            "completed": completed,
            "pending": total - completed,
            "overdue": len(self.overdue()),
        }

    def clear(self) -> None:
        self._db.execute("DELETE FROM reminders")
        self._db.commit()
        log.warning("All reminder memory cleared")

    def close(self) -> None:
        try:
            self._db.close()
        except Exception:  # pragma: no cover
            pass
