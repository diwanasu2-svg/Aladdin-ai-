"""
Notes tools — Task 34: FTS search now includes title column in FTS index.
CRUD with pin support + full-text search across title AND content.
"""
from __future__ import annotations
import sqlite3, time, uuid, logging
from pathlib import Path
from typing import Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_DB: Optional[Path] = None


def init_db(db_path: Path):
    global _DB
    _DB = db_path
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(str(db_path)) as conn:
        # Main notes table
        conn.execute("""CREATE TABLE IF NOT EXISTS notes (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            content TEXT DEFAULT '',
            pinned INTEGER DEFAULT 0,
            tags TEXT DEFAULT '',
            created_at REAL,
            updated_at REAL
        )""")

        # Task 34: FTS5 virtual table — indexes BOTH title AND content
        conn.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts
            USING fts5(id UNINDEXED, title, content, content='notes', content_rowid='rowid')
        """)

        # Task 34: Triggers to keep FTS index in sync with notes table
        conn.execute("""CREATE TRIGGER IF NOT EXISTS notes_fts_insert
            AFTER INSERT ON notes BEGIN
                INSERT INTO notes_fts(rowid, id, title, content) VALUES (new.rowid, new.id, new.title, COALESCE(new.content, ''));
            END
        """)
        conn.execute("""CREATE TRIGGER IF NOT EXISTS notes_fts_update
            AFTER UPDATE ON notes BEGIN
                INSERT INTO notes_fts(notes_fts, rowid, id, title, content) VALUES ('delete', old.rowid, old.id, old.title, COALESCE(old.content, ''));
                INSERT INTO notes_fts(rowid, id, title, content) VALUES (new.rowid, new.id, new.title, COALESCE(new.content, ''));
            END
        """)
        conn.execute("""CREATE TRIGGER IF NOT EXISTS notes_fts_delete
            AFTER DELETE ON notes BEGIN
                INSERT INTO notes_fts(notes_fts, rowid, id, title, content) VALUES ('delete', old.rowid, old.id, old.title, COALESCE(old.content, ''));
            END
        """)
        conn.commit()


def _conn():
    c = sqlite3.connect(str(_DB))
    c.execute("PRAGMA foreign_keys = ON")
    c.row_factory = sqlite3.Row
    return c


class CreateNoteTool(BaseTool):
    name = "create_note"
    description = "Create a new note."
    parameters = {"type": "object", "properties": {
        "title": {"type": "string"}, "content": {"type": "string"},
        "pinned": {"type": "boolean", "default": False},
        "tags": {"type": "string", "description": "Comma-separated tags"}},
        "required": ["title"]}

    async def execute(self, title: str, content: str = "", pinned: bool = False, tags: str = "") -> ToolResult:
        if not _DB:
            return ToolResult(False, self.name, error="DB not initialized")
        nid = str(uuid.uuid4())
        now = time.time()
        with _conn() as conn:
            conn.execute(
                "INSERT INTO notes VALUES (?,?,?,?,?,?,?)",
                (nid, title, content, 1 if pinned else 0, tags, now, now),
            )
        return ToolResult(True, self.name, {"id": nid, "title": title})


class ListNotesTool(BaseTool):
    name = "list_notes"
    description = "List all notes (pinned first). Searches title and content using FTS5."
    parameters = {"type": "object", "properties": {
        "search": {"type": "string", "description": "Optional full-text search query (searches title and content)"}}}

    async def execute(self, search: Optional[str] = None) -> ToolResult:
        if not _DB:
            return ToolResult(False, self.name, error="DB not initialized")
        with _conn() as conn:
            if search:
                # Task 34: FTS search across BOTH title and content using notes_fts
                try:
                    # Escape special FTS characters
                    safe_query = search.replace('"', '""')
                    rows = [dict(r) for r in conn.execute(
                        """SELECT n.* FROM notes n
                           JOIN notes_fts ON notes_fts.id = n.id
                           WHERE notes_fts MATCH ?
                           ORDER BY n.pinned DESC, n.updated_at DESC""",
                        (f'"{safe_query}"',),
                    ).fetchall()]
                    if not rows:
                        # Fallback: LIKE search across title AND content (Task 34)
                        q = f"%{search}%"
                        rows = [dict(r) for r in conn.execute(
                            "SELECT * FROM notes WHERE title LIKE ? OR content LIKE ? ORDER BY pinned DESC, updated_at DESC",
                            (q, q),
                        ).fetchall()]
                except Exception as exc:
                    log.warning("FTS search failed, falling back to LIKE: %s", exc)
                    q = f"%{search}%"
                    rows = [dict(r) for r in conn.execute(
                        "SELECT * FROM notes WHERE title LIKE ? OR content LIKE ? ORDER BY pinned DESC, updated_at DESC",
                        (q, q),
                    ).fetchall()]
            else:
                rows = [dict(r) for r in conn.execute(
                    "SELECT * FROM notes ORDER BY pinned DESC, updated_at DESC"
                ).fetchall()]
        return ToolResult(True, self.name, {"notes": rows, "count": len(rows)})


class UpdateNoteTool(BaseTool):
    name = "update_note"
    description = "Update a note."
    parameters = {"type": "object", "properties": {
        "id": {"type": "string"}, "title": {"type": "string"},
        "content": {"type": "string"}, "pinned": {"type": "boolean"},
        "tags": {"type": "string"}}, "required": ["id"]}

    async def execute(self, id: str, **fields) -> ToolResult:
        if not _DB:
            return ToolResult(False, self.name, error="DB not initialized")
        allowed = {"title", "content", "pinned", "tags"}
        fields = {k: (1 if v is True else 0 if v is False else v) for k, v in fields.items() if k in allowed}
        if not fields:
            return ToolResult(False, self.name, error="No fields to update")
        fields["updated_at"] = time.time()
        set_clause = ", ".join(f"{k}=?" for k in fields)
        with _conn() as conn:
            cur = conn.execute(f"UPDATE notes SET {set_clause} WHERE id=?", (*fields.values(), id))
        return ToolResult(cur.rowcount > 0, self.name, {"updated": id})


class DeleteNoteTool(BaseTool):
    name = "delete_note"
    description = "Delete a note by ID."
    parameters = {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}

    async def execute(self, id: str) -> ToolResult:
        if not _DB:
            return ToolResult(False, self.name, error="DB not initialized")
        with _conn() as conn:
            cur = conn.execute("DELETE FROM notes WHERE id=?", (id,))
        return ToolResult(
            cur.rowcount > 0, self.name,
            {"deleted": id} if cur.rowcount else None,
            error=None if cur.rowcount else "Not found",
        )
