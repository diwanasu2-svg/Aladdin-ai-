"""Calendar Memory — meetings, events, birthdays, appointments, important dates.

Part of Phase 3 — Smart Memory Part 2.

Designed to be a standalone memory layer, while leaving room for future
integrations (Google Calendar, iCalendar, CalDAV, etc.) via the optional
``external_id`` / ``source`` columns.
"""

from __future__ import annotations

import logging
import sqlite3
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


VALID_EVENT_TYPES = {
    "meeting",
    "event",
    "birthday",
    "appointment",
    "important",
    "other",
}


def _parse_dt(value: Any) -> Optional[datetime]:
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


class CalendarMemory:
    """Persistent calendar event store.

    Schema:
      events: id, title, event_type, start_at, end_at, all_day, location,
              description, attendees, recurrence ('none','yearly','monthly',
              'weekly','daily'), notes, source, external_id, created_at,
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
            CREATE TABLE IF NOT EXISTS calendar_events (
                id           TEXT PRIMARY KEY,
                title        TEXT NOT NULL,
                event_type   TEXT DEFAULT 'event',
                start_at     DATETIME,
                end_at       DATETIME,
                all_day      INTEGER DEFAULT 0,
                location     TEXT,
                description  TEXT,
                attendees    TEXT,
                recurrence   TEXT DEFAULT 'none',
                notes        TEXT,
                source       TEXT DEFAULT 'local',
                external_id  TEXT,
                created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS calendar_start_idx "
            "ON calendar_events(start_at)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS calendar_type_idx "
            "ON calendar_events(event_type)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS calendar_external_idx "
            "ON calendar_events(source, external_id)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # CRUD
    # ------------------------------------------------------------------
    def add_event(
        self,
        title: str,
        start_at: Any = None,
        end_at: Any = None,
        event_type: str = "event",
        all_day: bool = False,
        location: Optional[str] = None,
        description: Optional[str] = None,
        attendees: Optional[str] = None,
        recurrence: str = "none",
        notes: Optional[str] = None,
        source: str = "local",
        external_id: Optional[str] = None,
    ) -> str:
        if event_type not in VALID_EVENT_TYPES:
            event_type = "event"
        start = _parse_dt(start_at)
        end = _parse_dt(end_at)
        eid = str(uuid.uuid4())
        self._db.execute(
            "INSERT INTO calendar_events(id, title, event_type, start_at, "
            "end_at, all_day, location, description, attendees, recurrence, "
            "notes, source, external_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, "
            "?, ?, ?, ?)",
            (
                eid,
                title,
                event_type,
                start.isoformat() if start else None,
                end.isoformat() if end else None,
                int(bool(all_day)),
                location,
                description,
                attendees,
                recurrence,
                notes,
                source,
                external_id,
            ),
        )
        self._db.commit()
        log.info("Calendar event added: %s (%s)", title, eid)
        return eid

    # Convenience wrappers ------------------------------------------------
    def add_meeting(
        self, title: str, start_at: Any, end_at: Any = None, **kwargs: Any
    ) -> str:
        return self.add_event(title, start_at, end_at, event_type="meeting", **kwargs)

    def add_birthday(self, name: str, date: Any, **kwargs: Any) -> str:
        return self.add_event(
            title=f"{name}'s Birthday",
            start_at=date,
            event_type="birthday",
            all_day=True,
            recurrence="yearly",
            **kwargs,
        )

    def add_appointment(
        self, title: str, start_at: Any, end_at: Any = None, **kwargs: Any
    ) -> str:
        return self.add_event(
            title, start_at, end_at, event_type="appointment", **kwargs
        )

    def add_important_date(self, title: str, date: Any, **kwargs: Any) -> str:
        return self.add_event(
            title, date, event_type="important", all_day=True, **kwargs
        )

    # ------------------------------------------------------------------
    # Read / update / delete
    # ------------------------------------------------------------------
    def get(self, event_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM calendar_events WHERE id = ?", (event_id,)
        ).fetchone()
        return dict(row) if row else None

    def update_event(self, event_id: str, **fields: Any) -> bool:
        if not self.get(event_id):
            return False
        allowed = {
            "title",
            "event_type",
            "start_at",
            "end_at",
            "all_day",
            "location",
            "description",
            "attendees",
            "recurrence",
            "notes",
            "source",
            "external_id",
        }
        updates: List[str] = []
        values: List[Any] = []
        for k, v in fields.items():
            if k not in allowed:
                continue
            if k in ("start_at", "end_at"):
                dt = _parse_dt(v)
                v = dt.isoformat() if dt else None
            if k == "event_type" and v not in VALID_EVENT_TYPES:
                continue
            if k == "all_day":
                v = int(bool(v))
            updates.append(f"{k} = ?")
            values.append(v)
        if not updates:
            return False
        updates.append("updated_at = CURRENT_TIMESTAMP")
        values.append(event_id)
        self._db.execute(
            f"UPDATE calendar_events SET {', '.join(updates)} WHERE id = ?",
            values,
        )
        self._db.commit()
        return True

    def delete_event(self, event_id: str) -> bool:
        if not self.get(event_id):
            return False
        self._db.execute("DELETE FROM calendar_events WHERE id = ?", (event_id,))
        self._db.commit()
        log.info("Calendar event deleted: %s", event_id)
        return True

    # ------------------------------------------------------------------
    # Queries
    # ------------------------------------------------------------------
    def list_events(
        self,
        event_type: Optional[str] = None,
        limit: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        query = "SELECT * FROM calendar_events"
        params: List[Any] = []
        if event_type:
            query += " WHERE event_type = ?"
            params.append(event_type)
        query += " ORDER BY (start_at IS NULL), start_at ASC"
        if limit:
            query += " LIMIT ?"
            params.append(int(limit))
        rows = self._db.execute(query, params).fetchall()
        return [dict(r) for r in rows]

    def upcoming(self, days: int = 7, limit: int = 50) -> List[Dict[str, Any]]:
        now = datetime.now()
        end = now + timedelta(days=days)
        rows = self._db.execute(
            "SELECT * FROM calendar_events WHERE start_at IS NOT NULL "
            "AND start_at >= ? AND start_at <= ? "
            "ORDER BY start_at ASC LIMIT ?",
            (now.isoformat(), end.isoformat(), int(limit)),
        ).fetchall()
        return [dict(r) for r in rows]

    def events_on(self, date: Any) -> List[Dict[str, Any]]:
        d = _parse_dt(date)
        if not d:
            return []
        start = datetime(d.year, d.month, d.day)
        end = start + timedelta(days=1)
        rows = self._db.execute(
            "SELECT * FROM calendar_events WHERE start_at IS NOT NULL "
            "AND start_at >= ? AND start_at < ? "
            "ORDER BY start_at ASC",
            (start.isoformat(), end.isoformat()),
        ).fetchall()
        results = [dict(r) for r in rows]

        # Add recurring yearly events (e.g. birthdays) that match month/day
        yearly = self._db.execute(
            "SELECT * FROM calendar_events WHERE recurrence = 'yearly' "
            "AND start_at IS NOT NULL"
        ).fetchall()
        for r in yearly:
            sa = _parse_dt(r["start_at"])
            if sa and sa.month == d.month and sa.day == d.day and sa.year != d.year:
                results.append(dict(r))
        return results

    def birthdays(self) -> List[Dict[str, Any]]:
        return self.list_events(event_type="birthday")

    def search(self, query: str) -> List[Dict[str, Any]]:
        like = f"%{query.lower()}%"
        rows = self._db.execute(
            "SELECT * FROM calendar_events WHERE LOWER(title) LIKE ? "
            "OR LOWER(IFNULL(description,'')) LIKE ? "
            "OR LOWER(IFNULL(location,'')) LIKE ? "
            "OR LOWER(IFNULL(notes,'')) LIKE ? "
            "ORDER BY start_at ASC",
            (like, like, like, like),
        ).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------------
    # External integration hooks (future-friendly)
    # ------------------------------------------------------------------
    def get_by_external(
        self, source: str, external_id: str
    ) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM calendar_events WHERE source = ? AND external_id = ?",
            (source, external_id),
        ).fetchone()
        return dict(row) if row else None

    def upsert_external(self, source: str, external_id: str, **fields: Any) -> str:
        """Insert or update an event identified by (source, external_id).

        Useful as a hook for future Google Calendar / iCal sync.
        """
        existing = self.get_by_external(source, external_id)
        if existing:
            self.update_event(existing["id"], **fields)
            return existing["id"]
        return self.add_event(
            title=fields.pop("title", "Untitled"),
            source=source,
            external_id=external_id,
            **fields,
        )

    # ------------------------------------------------------------------
    # Stats / utility
    # ------------------------------------------------------------------
    def stats(self) -> Dict[str, Any]:
        total = self._db.execute(
            "SELECT COUNT(*) AS c FROM calendar_events"
        ).fetchone()["c"]
        rows = self._db.execute(
            "SELECT event_type, COUNT(*) AS c FROM calendar_events "
            "GROUP BY event_type"
        ).fetchall()
        return {
            "total": total,
            "by_type": {r["event_type"]: r["c"] for r in rows},
            "upcoming_7d": len(self.upcoming(days=7)),
        }

    def clear(self) -> None:
        self._db.execute("DELETE FROM calendar_events")
        self._db.commit()
        log.warning("All calendar memory cleared")

    def close(self) -> None:
        try:
            self._db.close()
        except Exception:  # pragma: no cover
            pass
