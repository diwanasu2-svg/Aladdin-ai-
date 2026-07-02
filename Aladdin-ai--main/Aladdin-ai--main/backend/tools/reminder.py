"""Reminder tools — CRUD backed by SQLite."""
from __future__ import annotations
import logging, sqlite3, time, uuid
from pathlib import Path
from typing import List, Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_DB: Optional[Path] = None


def init_db(db_path: Path):
    global _DB
    _DB = db_path
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(str(db_path)) as conn:
        conn.execute("""CREATE TABLE IF NOT EXISTS reminders (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT,
            remind_at REAL, done INTEGER DEFAULT 0, created_at REAL)""")


def _conn():
    c = sqlite3.connect(str(_DB)); c.row_factory = sqlite3.Row; return c
    c.execute("PRAGMA foreign_keys = ON")


class CreateReminderTool(BaseTool):
    name = "create_reminder"
    description = "Create a new reminder."
    parameters = {"type": "object", "properties": {
        "title": {"type": "string"}, "body": {"type": "string"},
        "remind_at": {"type": "number", "description": "Unix timestamp"}},
        "required": ["title"]}

    async def execute(self, title: str, body: str = "", remind_at: Optional[float] = None) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        rid = str(uuid.uuid4())
        with _conn() as conn:
            conn.execute("INSERT INTO reminders VALUES (?,?,?,?,0,?)",
                         (rid, title, body, remind_at, time.time()))
        return ToolResult(True, self.name, {"id": rid, "title": title, "remind_at": remind_at})


class ListRemindersTool(BaseTool):
    name = "list_reminders"
    description = "List all active reminders."
    parameters = {"type": "object", "properties": {"include_done": {"type": "boolean", "default": False}}}

    async def execute(self, include_done: bool = False) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        q = "SELECT * FROM reminders" + ("" if include_done else " WHERE done=0") + " ORDER BY remind_at ASC"
        with _conn() as conn:
            rows = [dict(r) for r in conn.execute(q).fetchall()]
        return ToolResult(True, self.name, {"reminders": rows, "count": len(rows)})


class DeleteReminderTool(BaseTool):
    name = "delete_reminder"
    description = "Delete a reminder by ID."
    parameters = {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}

    async def execute(self, id: str) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        with _conn() as conn:
            cur = conn.execute("DELETE FROM reminders WHERE id=?", (id,))
        return ToolResult(cur.rowcount > 0, self.name, {"deleted": id} if cur.rowcount else None,
                          error=None if cur.rowcount else "Not found")


class UpdateReminderTool(BaseTool):
    name = "update_reminder"
    description = "Update a reminder."
    parameters = {"type": "object", "properties": {
        "id": {"type": "string"}, "title": {"type": "string"},
        "body": {"type": "string"}, "remind_at": {"type": "number"},
        "done": {"type": "boolean"}}, "required": ["id"]}

    async def execute(self, id: str, **fields) -> ToolResult:
        if not _DB: return ToolResult(False, self.name, error="DB not initialized")
        allowed = {"title", "body", "remind_at", "done"}
        fields = {k: (1 if v is True else 0 if v is False else v) for k, v in fields.items() if k in allowed}
        if not fields: return ToolResult(False, self.name, error="No fields to update")
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with _conn() as conn:
            cur = conn.execute(f"UPDATE reminders SET {set_clause} WHERE id=?", (*fields.values(), id))
        return ToolResult(cur.rowcount > 0, self.name, {"updated": id})
