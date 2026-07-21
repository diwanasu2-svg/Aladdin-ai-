"""Calendar tools — CRUD backed by SQLite."""
from __future__ import annotations
import sqlite3, time, uuid, logging
from pathlib import Path
from typing import Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_DB: Optional[Path] = None


def init_db(db_path: Path):
    global _DB; _DB = db_path
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(str(db_path)) as conn:
        conn.execute("""CREATE TABLE IF NOT EXISTS cal_events (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT,
            start_ts REAL, end_ts REAL, location TEXT,
            reminder_minutes INTEGER DEFAULT 15, created_at REAL)""")


def _conn():
    c = sqlite3.connect(str(_DB)); c.row_factory = sqlite3.Row; return c
    c.execute("PRAGMA foreign_keys = ON")


class CreateEventTool(BaseTool):
    name = "create_calendar_event"
    description = "Create a new calendar event."
    parameters = {"type": "object", "properties": {
        "title": {"type": "string"}, "description": {"type": "string"},
        "start_ts": {"type": "number", "description": "Unix timestamp"},
        "end_ts": {"type": "number"}, "location": {"type": "string"},
        "reminder_minutes": {"type": "integer", "default": 15}},
        "required": ["title", "start_ts"]}

    async def execute(self, title: str, start_ts: float, end_ts: Optional[float] = None,
                      description: str = "", location: str = "", reminder_minutes: int = 15) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        eid = str(uuid.uuid4())
        with _conn() as conn:
            conn.execute("INSERT INTO cal_events VALUES (?,?,?,?,?,?,?,?)",
                         (eid, title, description, start_ts, end_ts or start_ts+3600, location, reminder_minutes, time.time()))
        return ToolResult(True, self.name, {"id": eid, "title": title, "start_ts": start_ts})


class ListEventsTool(BaseTool):
    name = "list_calendar_events"
    description = "List upcoming calendar events."
    parameters = {"type": "object", "properties": {
        "from_ts": {"type": "number", "description": "Start Unix timestamp (default: now)"},
        "limit": {"type": "integer", "default": 20}}}

    async def execute(self, from_ts: Optional[float] = None, limit: int = 20) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        ts = from_ts or time.time()
        with _conn() as conn:
            rows = [dict(r) for r in conn.execute(
                "SELECT * FROM cal_events WHERE start_ts>=? ORDER BY start_ts LIMIT ?", (ts, limit)).fetchall()]
        return ToolResult(True, self.name, {"events": rows, "count": len(rows)})


class DeleteEventTool(BaseTool):
    name = "delete_calendar_event"
    description = "Delete a calendar event by ID."
    parameters = {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}

    async def execute(self, id: str) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        with _conn() as conn:
            cur = conn.execute("DELETE FROM cal_events WHERE id=?", (id,))
        return ToolResult(cur.rowcount > 0, self.name, {"deleted": id} if cur.rowcount else None,
                          error=None if cur.rowcount else "Not found")


class UpdateEventTool(BaseTool):
    name = "update_calendar_event"
    description = "Update a calendar event."
    parameters = {"type": "object", "properties": {
        "id": {"type": "string"}, "title": {"type": "string"},
        "description": {"type": "string"}, "start_ts": {"type": "number"},
        "end_ts": {"type": "number"}, "location": {"type": "string"}},
        "required": ["id"]}

    async def execute(self, id: str, **fields) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        allowed = {"title", "description", "start_ts", "end_ts", "location", "reminder_minutes"}
        fields = {k: v for k, v in fields.items() if k in allowed}
        if not fields: return ToolResult(False, self.name, error="No fields to update")
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with _conn() as conn:
            cur = conn.execute(f"UPDATE cal_events SET {set_clause} WHERE id=?", (*fields.values(), id))
        return ToolResult(cur.rowcount > 0, self.name, {"updated": id})
